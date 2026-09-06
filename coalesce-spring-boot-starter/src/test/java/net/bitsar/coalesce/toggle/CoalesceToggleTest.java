package net.bitsar.coalesce.toggle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch itself is trivial; what matters is that it reports the position it replaced,
 * so a caller flipping it can tell whether it actually changed anything.
 */
class CoalesceToggleTest {

    @Test
    void startsWhereItIsTold() {
        assertThat(new CoalesceToggle(true).isActive()).isTrue();
        assertThat(new CoalesceToggle(false).isActive()).isFalse();
    }

    @Test
    void reportsThePositionItReplaced() {
        CoalesceToggle toggle = new CoalesceToggle(true);

        assertThat(toggle.setActive(false)).isTrue();
        assertThat(toggle.isActive()).isFalse();

        assertThat(toggle.setActive(true)).isFalse();
        assertThat(toggle.isActive()).isTrue();
    }

    @Test
    void settingTheSamePositionTwiceIsHarmless() {
        CoalesceToggle toggle = new CoalesceToggle(false);

        assertThat(toggle.setActive(false)).isFalse();
        assertThat(toggle.isActive()).isFalse();
    }
}
