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

/**
 * Canonical settlement data cannot be reconciled with the Rollup0 rules. The follower halts rather
 * than guess (draft 10.7).
 */
public class DerivationException extends RuntimeException {

  public DerivationException(final String message) {
    super(message);
  }

  public DerivationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
