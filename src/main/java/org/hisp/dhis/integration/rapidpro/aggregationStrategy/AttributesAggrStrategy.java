package org.hisp.dhis.integration.rapidpro.aggregationStrategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.hisp.dhis.api.model.v40_0.Dxf2EventsTrackedentityAttribute;
import org.hisp.dhis.api.model.v40_0.Enrollment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AttributesAggrStrategy implements AggregationStrategy

{
    @Autowired
    private ObjectMapper objectMapper;

    protected static final Logger LOGGER = LoggerFactory.getLogger( AttributesAggrStrategy.class );

    @Override
    public Exchange aggregate(Exchange original, Exchange resource)
    {
        String phoneNumberAttributeId = original.getContext().resolvePropertyPlaceholders( "{{dhis2.phone.number.attribute.uid}}" );
        String givenNameAttributeId = original.getContext().resolvePropertyPlaceholders( "{{dhis2.given.name.attribute.uid}}" );

        String resourceBody = resource.getMessage().getBody(String.class);
        Map<String,Object> originalBody = original.getMessage().getBody(Map.class);
        LOGGER.debug( String.format( "resourceBody from enrollment: %s", resourceBody) );
        List<Dxf2EventsTrackedentityAttribute> enrollmentAttributes = null;
        try
        {
            enrollmentAttributes = objectMapper.readValue( resourceBody, Enrollment.class ).getAttributes().get();
        }
        catch ( JsonProcessingException e )
        {
            throw new RuntimeException( e );
        }
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
        originalBody.put("phoneNumber",phoneNumber);
        originalBody.put("givenName",givenName);
        original.getIn().setBody( originalBody );
        return original;

    }
}
