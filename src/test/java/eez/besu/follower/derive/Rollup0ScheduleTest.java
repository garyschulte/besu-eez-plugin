/*
 * Copyright contributors to besu-eez-plugin.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package eez.besu.follower.derive;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Rollup0ScheduleTest {

  private static final long T = 1_700_000_000L;
  private final Rollup0Schedule schedule = new Rollup0Schedule(T, 2, 6);

  @Test
  void blocksAreTwoSecondsApartWithSyncEverySixth() {
    // the worked timeline in draft 4.1
    assertThat(schedule.timestamp(0)).isEqualTo(T);
    assertThat(schedule.timestamp(1)).isEqualTo(T + 2);
    assertThat(schedule.timestamp(6)).isEqualTo(T + 12);
    assertThat(schedule.isSync(0)).isTrue();
    assertThat(schedule.isSync(5)).isFalse();
    assertThat(schedule.isSync(6)).isTrue();
    assertThat(schedule.isSync(12)).isTrue();
  }

  @Test
  void anIntervalCopiesTheSeedOfTheSyncThatOpensIt() {
    // blocks 1..6 lie in (T, T + 12] and use P(T); blocks 7..12 use P(T + 12)
    assertThat(schedule.intervalOpeningSyncTimestamp(0)).isEqualTo(T);
    assertThat(schedule.intervalOpeningSyncTimestamp(1)).isEqualTo(T);
    assertThat(schedule.intervalOpeningSyncTimestamp(6)).isEqualTo(T);
    assertThat(schedule.intervalOpeningSyncTimestamp(7)).isEqualTo(T + 12);
    assertThat(schedule.intervalOpeningSyncTimestamp(12)).isEqualTo(T + 12);
    assertThat(schedule.intervalOpeningSyncTimestamp(13)).isEqualTo(T + 24);
  }
}
