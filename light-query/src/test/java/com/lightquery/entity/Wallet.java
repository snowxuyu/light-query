package com.lightquery.entity;

import com.lightquery.ValueConverter;

import java.util.Objects;

/** Test entity for the global ValueConverter registry: a wallet holding {@link Money}. */
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "t_wallet")
public class Wallet {

    /** Immutable monetary value — not a JDBC-native type; needs a converter. */
    public static final class Money {
        private final long cents;

        public Money(long cents) {
            this.cents = cents;
        }

        public long getCents() {
            return cents;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Money m && m.cents == cents;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cents);
        }

        @Override
        public String toString() {
            return "Money(" + cents + ")";
        }
    }

    /** Global-style converter: stores raw cents. */
    public static class MoneyConverter implements ValueConverter<Money, Long> {
        @Override
        public Long toDatabase(Money attribute) {
            return attribute.cents;
        }

        @Override
        public Money fromDatabase(Long dbValue) {
            return dbValue == null ? null : new Money(dbValue);
        }
    }

    /** Field-level JPA converter with an observable marker (+1 billion) to prove precedence. */
    public static class MarkingMoneyConverter
            implements jakarta.persistence.AttributeConverter<Money, Long> {
        @Override
        public Long convertToDatabaseColumn(Money attribute) {
            return attribute.cents + 1_000_000_000L;
        }

        @Override
        public Money convertToEntityAttribute(Long dbValue) {
            return dbValue == null ? null : new Money(dbValue - 1_000_000_000L);
        }
    }

    public static class ExplodingConverter implements ValueConverter<Money, Long> {
        @Override
        public Long toDatabase(Money attribute) {
            throw new IllegalStateException("boom");
        }

        @Override
        public Money fromDatabase(Long dbValue) {
            throw new IllegalStateException("boom");
        }
    }

    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    private String owner;

    @jakarta.persistence.Column(name = "amount_cents")
    private Money amount;

    @jakarta.persistence.Column(name = "bonus_cents")
    @jakarta.persistence.Convert(converter = MarkingMoneyConverter.class)
    private Money bonus;

    public Wallet() {
    }

    public Wallet(String owner, Money amount, Money bonus) {
        this.owner = owner;
        this.amount = amount;
        this.bonus = bonus;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getBonus() {
        return bonus;
    }
}
