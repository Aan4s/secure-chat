package org.example.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilenamesTest {

    @Test
    void rejectsTraversalDots() {
        assertThatThrownBy(() -> Filenames.sanitize(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Filenames.sanitize("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stripsPathComponents() {
        // Path traversal attempts collapse to a basename
        assertThat(Filenames.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(Filenames.sanitize("/etc/passwd")).isEqualTo("passwd");
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> Filenames.sanitize(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Filenames.sanitize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Filenames.sanitize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsNormalFilenames() {
        assertThat(Filenames.sanitize("photo.jpg")).isEqualTo("photo.jpg");
        assertThat(Filenames.sanitize("report-2024_v2.pdf")).isEqualTo("report-2024_v2.pdf");
    }

    @Test
    void replacesUnsafeCharacters() {
        assertThat(Filenames.sanitize("My Document.pdf")).isEqualTo("My_Document.pdf");
        assertThat(Filenames.sanitize("a;b&c|d.txt")).isEqualTo("a_b_c_d.txt");
    }

    @Test
    void stripsLeadingDots() {
        assertThat(Filenames.sanitize(".hidden")).isEqualTo("hidden");
        assertThat(Filenames.sanitize("...bashrc")).isEqualTo("bashrc");
    }

    @Test
    void truncatesOverlyLongNames() {
        String huge = "a".repeat(500) + ".jpg";
        String result = Filenames.sanitize(huge);
        assertThat(result.length()).isLessThanOrEqualTo(200);
    }
}
