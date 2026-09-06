package net.bitsar.coalesce.toggle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

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

    // ---------- per-namespace overrides ----------

    /** One sick dependency should not cost every healthy one its shield. */
    @Test
    void oneNamespaceCanBeBypassedWhileTheRestKeepCoalescing() {
        CoalesceToggle toggle = new CoalesceToggle(true);

        toggle.setActive("OrderService.getOrder", false);

        assertThat(toggle.isActive("OrderService.getOrder")).isFalse();
        assertThat(toggle.isActive("PriceService.quote")).isTrue();
        assertThat(toggle.isActive()).isTrue();
    }

    /** The big red button cannot be undermined by a stale per-method override. */
    @Test
    void theGlobalSwitchWinsOverAnyOverride() {
        CoalesceToggle toggle = new CoalesceToggle(true);
        toggle.setActive("OrderService.getOrder", true);

        toggle.setActive(false);

        assertThat(toggle.isActive("OrderService.getOrder")).isFalse();
        assertThat(toggle.isActive("anything.else")).isFalse();
    }

    /** With no override, a namespace follows the global switch. */
    @Test
    void anUnknownNamespaceFollowsTheGlobalSwitch() {
        assertThat(new CoalesceToggle(true).isActive("never.configured")).isTrue();
        assertThat(new CoalesceToggle(false).isActive("never.configured")).isFalse();
    }

    @Test
    void overridingReportsThePositionItReplaced() {
        CoalesceToggle toggle = new CoalesceToggle(true);

        // No override yet, so the position it replaced is the global one.
        assertThat(toggle.setActive("OrderService.getOrder", false)).isTrue();
        assertThat(toggle.setActive("OrderService.getOrder", true)).isFalse();
    }

    @Test
    void clearingAnOverrideReturnsTheNamespaceToTheGlobalSwitch() {
        CoalesceToggle toggle = new CoalesceToggle(true);
        toggle.setActive("OrderService.getOrder", false);

        assertThat(toggle.clearOverride("OrderService.getOrder")).isTrue();
        assertThat(toggle.isActive("OrderService.getOrder")).isTrue();
        assertThat(toggle.overrides()).isEmpty();

        // Clearing one that was never set is a no-op, not an error.
        assertThat(toggle.clearOverride("OrderService.getOrder")).isFalse();
    }

    @Test
    void overridesAreReportedAndCannotBeMutatedThroughTheView() {
        CoalesceToggle toggle = new CoalesceToggle(true);
        toggle.setActive("OrderService.getOrder", false);

        assertThat(toggle.overrides()).containsExactly(entry("OrderService.getOrder", false));
        assertThatThrownBy(() -> toggle.overrides().put("x", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aBlankNamespaceIsRejectedRatherThanSilentlyOverridingNothing() {
        CoalesceToggle toggle = new CoalesceToggle(true);

        assertThatThrownBy(() -> toggle.setActive("  ", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> toggle.setActive(null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
