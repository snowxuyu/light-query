package com.lightquery.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Invalid: SEQUENCE strategy without a @SequenceGenerator — must fail at startup. */
@Table(name = "t_sequenced_bad")
public class SequencedBad {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;
}
