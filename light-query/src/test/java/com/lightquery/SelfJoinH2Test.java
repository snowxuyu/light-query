package com.lightquery;

import com.lightquery.entity.Employee;
import com.lightquery.exception.SqlBuildException;
import com.lightquery.support.TestSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T14 — self-join via QueryTable / TableColumn. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfJoinH2Test {

    private TestSupport support;
    private LightQuerySession db;

    private QueryTable<Employee> staff;
    private QueryTable<Employee> manager;

    @BeforeAll
    void setUp() {
        support = TestSupport.employeeDb("selfjoin");
        db = support.session();
        db.insertBatch(List.of(
                new Employee("Alice", null, "20000", 0),
                new Employee("Bob", 1L, "12000", 0),
                new Employee("Carol", 1L, "11000", 0),
                new Employee("Dave", 1L, "9000", 1),
                new Employee("Erin", 4L, "8000", 0)));

        staff = QueryTable.of(Employee.class, "staff");
        manager = QueryTable.of(Employee.class, "mgr");
    }

    @Test
    void selfJoinProjectsBothOccurrences() {
        List<Tuple> rows = db.queryable(staff)
                .leftJoin(manager, on -> on.eqColumn(
                        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                .eq(manager.col(Employee::getName), "Alice")
                .select(staff.col(Employee::getName), manager.col(Employee::getName).as("manager_name"))
                .orderByAsc(staff.col(Employee::getName))
                .toTupleList();
        assertEquals(2, rows.size());
        assertEquals("Bob", rows.get(0).get("name"));
        assertEquals("Alice", rows.get(0).get("manager_name"));
        assertEquals("Carol", rows.get(1).get("name"));
    }

    @Test
    void joinedOccurrenceIsNotLogicDeleteFiltered() {
        // Erin's manager Dave is logically deleted — the join still matches
        List<Tuple> rows = db.queryable(staff)
                .leftJoin(manager, on -> on.eqColumn(
                        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                .eq(staff.col(Employee::getName), "Erin")
                .select(manager.col(Employee::getName).as("manager_name"))
                .toTupleList();
        assertEquals(1, rows.size());
        assertEquals("Dave", rows.get(0).get("manager_name"));
    }

    @Test
    void rootOccurrenceStaysLogicDeleteFiltered() {
        // Dave (deleted=1) is a staff occurrence → filtered from the root side
        long count = db.queryable(staff)
                .leftJoin(manager, on -> on.eqColumn(
                        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                .eq(staff.col(Employee::getName), "Dave")
                .count();
        assertEquals(0, count);
    }

    @Test
    void lambdaOnDuplicatedEntityIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () ->
                db.queryable(staff)
                        .leftJoin(manager, on -> on.eqColumn(
                                staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                        .eq(Employee::getName, "Bob"));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
        assertTrue(e.getMessage().contains("QueryTable"), e.getMessage());
    }

    @Test
    void plainClassSelfJoinIsRejected() {
        SqlBuildException e = assertThrows(SqlBuildException.class, () ->
                db.queryable(Employee.class)
                        .leftJoin(Employee.class, on -> on.eq(
                                Employee::getManagerId, Employee::getId)));
        assertTrue(e.getMessage().contains("QueryTable.of"), e.getMessage());
    }

    @Test
    void unregisteredOccurrenceIsRejected() {
        QueryTable<Employee> unknown = QueryTable.of(Employee.class, "ghost");
        SqlBuildException e = assertThrows(SqlBuildException.class, () ->
                db.queryable(staff).eq(unknown.col(Employee::getName), "x"));
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void duplicateAliasIsRejected() {
        QueryTable<Employee> otherManager = QueryTable.of(Employee.class, "mgr");
        SqlBuildException e = assertThrows(SqlBuildException.class, () ->
                db.queryable(staff)
                        .leftJoin(manager, on -> on.eqColumn(
                                staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                        .leftJoin(otherManager, on -> on.eqColumn(
                                staff.col(Employee::getManagerId), otherManager.col(Employee::getId))));
        assertTrue(e.getMessage().contains("already used"), e.getMessage());
    }

    @Test
    void reservedAliasIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> QueryTable.of(Employee.class, "t0"));
        assertTrue(e.getMessage().contains("reserved"), e.getMessage());
    }

    @Test
    void leftJoinWithoutManagerWorks() {
        List<Tuple> rows = db.queryable(staff)
                .leftJoin(manager, on -> on.eqColumn(
                        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                .isNull(manager.col(Employee::getId))
                .select(staff.col(Employee::getName))
                .toTupleList();
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
    }

    @Test
    void conditionsMixOccurrences() {
        // staff earns more than 10k and its manager earns more than 15k → only Bob
        List<Tuple> rows = db.queryable(staff)
                .leftJoin(manager, on -> on.eqColumn(
                        staff.col(Employee::getManagerId), manager.col(Employee::getId)))
                .gt(staff.col(Employee::getSalary), new java.math.BigDecimal("11000"))
                .gt(manager.col(Employee::getSalary), new java.math.BigDecimal("15000"))
                .select(staff.col(Employee::getName))
                .toTupleList();
        assertEquals(1, rows.size());
        assertEquals("Bob", rows.get(0).get("name"));
    }
}
