package com.lightquery.entity;

import com.lightquery.annotation.LogicDelete;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Test entity with every mapping feature light-query supports. */
@Table(name = "t_user")
public class User {

    public enum Status { ACTIVE, FROZEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name")
    private String name;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Integer age;

    private BigDecimal balance;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "remark")
    private String remark;

    @LogicDelete
    private Integer deleted;

    @Transient
    private String ignored;

    public User() {
    }

    public User(String name, Status status, Integer age, String balance,
                LocalDateTime createdAt, String remark, Integer deleted) {
        this.name = name;
        this.status = status;
        this.age = age;
        this.balance = balance == null ? null : new BigDecimal(balance);
        this.createdAt = createdAt;
        this.remark = remark;
        this.deleted = deleted;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public String getIgnored() {
        return ignored;
    }

    public void setIgnored(String ignored) {
        this.ignored = ignored;
    }
}
