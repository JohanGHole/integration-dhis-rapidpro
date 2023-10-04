package org.hisp.dhis.integration.rapidpro.aggregationStrategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.hisp.dhis.api.model.v40_0.Enrollment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RapidProContactEnricher implements AggregationStrategy
{
    @Autowired
    private ObjectMapper objectMapper;

    protected static final Logger LOGGER = LoggerFactory.getLogger( AttributesAggrStrategy.class );
    @Override
    public Exchange aggregate( Exchange original, Exchange resource )
    {
        Map<String,Object> originalBody = original.getMessage().getBody( Map.class);
        String resourceBody = resource.getMessage().getBody(String.class);
        Map<String,Object> resourceBodyMap;
        try
        {
            resourceBodyMap = objectMapper.readValue( resourceBody, Map.class );
        }
        catch ( JsonProcessingException e )
        {
            throw new RuntimeException( e );
        }
        if (resourceBodyMap.get("results") != null) {
            originalBody.put("results",resourceBodyMap.get("results"));
        } else
        {
            LOGGER.warn( String.format( "unexpected result from RapidPro => %s", resourceBody) );
        }
        original.getMessage().setBody( originalBody );
        return original;

    }
}
