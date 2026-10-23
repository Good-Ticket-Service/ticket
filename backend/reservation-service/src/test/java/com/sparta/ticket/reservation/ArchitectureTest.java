package com.sparta.ticket.reservation;

import com.sparta.ticket.common.support.LayeredArchitectureRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

@AnalyzeClasses(packages = "com.sparta.ticket.reservation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests LAYERS = ArchTests.in(LayeredArchitectureRules.class);
}
