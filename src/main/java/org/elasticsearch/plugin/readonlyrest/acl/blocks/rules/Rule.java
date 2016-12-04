/*
 * This file is part of ReadonlyREST.
 *
 *     ReadonlyREST is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ReadonlyREST is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with ReadonlyREST.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.base.CaseFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;

/**
 * Created by sscarduzio on 13/02/2016.
 */
abstract public class Rule {
  private Block.Policy policy = null;
  final public String KEY;

  protected RuleExitResult MATCH;
  protected RuleExitResult NO_MATCH;

  public Rule(Settings s) {
    KEY = CaseFormat.LOWER_CAMEL.to(
        CaseFormat.LOWER_UNDERSCORE,
        getClass().getSimpleName().replace("Rule", "")
    );
    MATCH = new RuleExitResult(true, this);
    NO_MATCH = new RuleExitResult(false, this);
  }

  public abstract RuleExitResult match(RequestContext rc);

  public Block.Policy getPolicy() {
    return policy;
  }

}
