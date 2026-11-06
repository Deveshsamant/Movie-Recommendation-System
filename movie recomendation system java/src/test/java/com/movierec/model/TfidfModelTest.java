package com.movierec.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.movierec.math.CsrMatrix;
import java.util.List;
import org.junit.jupiter.api.Test;

class TfidfModelTest {

    /** Reference values come from sklearn's TfidfVectorizer with the same settings. */
    @Test
    void matchesScikitLearn() {
        TfidfModel model = TfidfModel.fit(List.of(
                "g_a g_a g_a t_x t_x w_foo",
                "g_a g_a g_a g_b g_b g_b t_x t_x",
                "g_b g_b g_b t_y t_y w_foo",
                "g_c unknownterm",
                "t_y t_y t_y g_a"));

        assertArrayEquals(new String[] {"g_a", "g_b", "t_x", "t_y", "w_foo"}, model.terms);

        CsrMatrix m = model.matrix;
        assertArrayEquals(new int[] {0, 3, 6, 9, 9, 11}, m.indptr); // document 3 has no surviving term
        assertArrayEquals(new int[] {0, 2, 4, 0, 1, 2, 1, 3, 4, 0, 3}, m.indices);
        assertArrayEquals(new double[] {
                0.6631131127193604, 0.6445027273838313, 0.38065369318377035,
                0.5426509643376818, 0.6537251938849068, 0.5274213702378064,
                0.7297183669435993, 0.5887321837696325, 0.3477147117091919,
                0.3678146214816091, 0.929899136586512
        }, m.data, 1e-14);
    }

    @Test
    void slugsLikeThePythonVersion() {
        assertEquals("sci_fi", ContentBasedRecommender.slug(" Sci-Fi! "));
        assertEquals("am_lie", ContentBasedRecommender.slug("Amélie"));
        assertEquals("", ContentBasedRecommender.slug("---"));
    }
}
