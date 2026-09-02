package com.sandeep.awsdocumentapi.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StorageKeysTest {

    @ParameterizedTest
    @CsvSource({
            "proposal.pdf,                     proposal.pdf",
            "Q3 report (final).docx,           Q3_report__final_.docx",
            "C:\\Users\\me\\proposal.pdf,      proposal.pdf",
            "../../etc/passwd,                 passwd",
            "..,                               file",
            "'',                               file",
            "résumé.pdf,                       r_sum_.pdf",
    })
    void sanitisesFilenames(String input, String expected) {
        assertThat(StorageKeys.sanitise(input)).isEqualTo(expected);
    }
}
