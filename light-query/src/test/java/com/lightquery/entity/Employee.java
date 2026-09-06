package com.lightquery.entity;

import com.lightquery.annotation.LogicDelete;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Employee entity used for self-join tests (manager is another employee row). */
@Table(name = "t_employee")
public class Employee {

    public enum Status { ACTIVE, FROZEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(name = "manager_id")
    private Long managerId;

    private BigDecimal salary;

    @LogicDelete
    private Integer deleted;

    public Employee() {
    }

    public Employee(String name, Long managerId, String salary, int deleted) {
        this.name = name;
        this.managerId = managerId;
        this.salary = salary == null ? null : new BigDecimal(salary);
        this.deleted = deleted;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getManagerId() {
        return managerId;
    }

    public void setManagerId(Long managerId) {
        this.managerId = managerId;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
