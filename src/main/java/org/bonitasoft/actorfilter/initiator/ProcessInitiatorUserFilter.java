/**
 * Copyright (C) 2012 BonitaSoft S.A.
 * BonitaSoft, 31 rue Gustave Eiffel - 38000 Grenoble
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2.0 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.bonitasoft.actorfilter.initiator;

import org.bonitasoft.engine.connector.ConnectorValidationException;
import org.bonitasoft.engine.exception.BonitaException;
import org.bonitasoft.engine.filter.AbstractUserFilter;
import org.bonitasoft.engine.filter.UserFilterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static java.util.Collections.singletonList;

/**
 * @author Baptiste Mesta
 * @author Emmanuel Duchastenier
 */
public class ProcessInitiatorUserFilter extends AbstractUserFilter {

    static final String AUTO_ASSIGN = "autoAssign";

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessInitiatorUserFilter.class);

    @Override
    public void validateInputParameters() throws ConnectorValidationException {
        // no params to validate
    }

    @Override
    public List<Long> filter(final String actorName) throws UserFilterException {
        final long processInstanceId = getExecutionContext().getProcessInstanceId();
        try {
            long processInitiator = getAPIAccessor().getProcessAPI().getProcessInstance(processInstanceId).getStartedBy();
            if (processInitiator == 0) {
                LOGGER.warn("No process initiator found for process instance {}. Process may have been started by the system", processInstanceId);
                throw new UserFilterException("No process initiator found for process instance " + processInstanceId);
            }
            return singletonList(processInitiator);
        } catch (final BonitaException e) {
            throw new UserFilterException(e);
        }
    }

    @Override
    public boolean shouldAutoAssignTaskIfSingleResult() {
        final Boolean autoAssign = (Boolean) getInputParameter(AUTO_ASSIGN);
        return autoAssign == null || autoAssign;
    }

}

