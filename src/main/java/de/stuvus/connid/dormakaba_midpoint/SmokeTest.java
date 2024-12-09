package de.stuvus.connid.dormakaba_midpoint;

import org.apache.olingo.client.api.uri.URIFilter;
import org.apache.olingo.client.core.ODataClientFactory;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

import java.util.Arrays;
import java.util.Collections;

public class SmokeTest {

    public static void main(String[] args) {
        DormakabaConnector conn = new DormakabaConnector();
        DormakabaConnectorConfiguration config = new DormakabaConnectorConfiguration();
        config.setExosEndpointProperty("xxxx");
        config.setExosUsernameProperty("xxx");
        config.setExosPasswordProperty("xxxx");
        conn.init(config);


        conn.executeQuery(DormakabaConnector.OBJECT_CLASS_DOORACCESSRIGHT, null, new ResultsHandler() {
            @Override
            public boolean handle(ConnectorObject connectorObject) {
                System.out.println(connectorObject);
                return true;
            }
        }, new OperationOptionsBuilder()
                .setPageSize(20)
                .build());

        URIFilter filter = conn.createFilterTranslator(ObjectClass.ACCOUNT, new OperationOptionsBuilder().build())
                .translate(new EqualsFilter(AttributeBuilder.build(DormakabaConnector.ATTRIBUTE_MATNR_NAME, "3625330"))).get(0);


        conn.executeQuery(ObjectClass.ACCOUNT, filter, new ResultsHandler() {
            @Override
            public boolean handle(ConnectorObject connectorObject) {
                System.out.println(connectorObject);
                return true;
            }
        }, new OperationOptionsBuilder()
                .setAttributesToGet(DormakabaConnector.ATTRIBUTE_ACCESSRIGHTS_NAME)
                .setPageSize(20)
                .build());

        conn.updateDelta(ObjectClass.ACCOUNT, new Uid("508A3F2A-B82A-4CB1-817E-626C240FD7DE"),
                Collections.singleton(
                        AttributeDeltaBuilder.build(DormakabaConnector.ATTRIBUTE_ACCESSRIGHTS_NAME,
                                Arrays.asList(),
                                Arrays.asList("7EE84132-E39A-44C3-B2DE-8F0F4ED2D907", "EF64128B-C188-4A59-B4CB-42B945B734BB"))),
                null);
    }
}
