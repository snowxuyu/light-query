package com.lightquery.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** Entity whose key is populated from a database sequence. */
@Table(name = "t_sequenced_invoice")
public class SequencedInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invoice_seq_gen")
    @SequenceGenerator(name = "invoice_seq_gen", sequenceName = "invoice_seq")
    private Long id;

    private String ref;

    public SequencedInvoice() {
    }

    public SequencedInvoice(String ref) {
        this.ref = ref;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }
}
