/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.integration.rapidpro.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.hisp.dhis.api.model.v40_0.Dxf2EventsTrackedentityAttribute;
import org.hisp.dhis.api.model.v40_0.Enrollment;
import org.hisp.dhis.api.model.v40_0.TrackedEntity;
import org.hisp.dhis.api.model.v40_0.WebapiControllerTrackerViewAttribute;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FilterTrackedEntityAttributes implements Processor
{
    // FIXME: the attribute filter request parameter is not working in Tracker v40, so the api request returns all attributes for the tracked entity
    // Once this is fixed, this processor can be removed

    @Override
    public void process( Exchange exchange ) throws Exception
    {
        String phoneNumberAttributeId = exchange.getContext().resolvePropertyPlaceholders( "{{dhis2.phone.number.attribute.uid}}" );
        String givenNameAttributeId = exchange.getContext().resolvePropertyPlaceholders( "{{dhis2.given.name.attribute.uid}}" );
        String jsonResponse = exchange.getMessage().getBody(String.class);

        ObjectMapper mapper = new ObjectMapper();
        Enrollment enrollment = mapper.readValue(jsonResponse, Enrollment.class);
        List<Dxf2EventsTrackedentityAttribute> enrollmentAttributes = enrollment.getAttributes().get();
        String phoneNumber = null;
        String givenName = null;
        for ( Dxf2EventsTrackedentityAttribute attribute : enrollmentAttributes )
        {
            if ( attribute.getAttribute().get().toString().equals(phoneNumberAttributeId) )
            {
                phoneNumber = attribute.getValue().get();
            }
            if ( attribute.getAttribute().get().toString().equals(givenNameAttributeId))
            {
                givenName = attribute.getValue().get();
            }

        }
        exchange.setProperty( "phoneNumber", phoneNumber );
        exchange.setProperty("givenName", givenName);
        exchange.setProperty("trackedEntityId",enrollment.getTrackedEntityInstance());
    }
}
