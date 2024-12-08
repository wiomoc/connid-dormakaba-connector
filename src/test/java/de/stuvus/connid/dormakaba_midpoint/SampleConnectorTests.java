package de.stuvus.connid.dormakaba_midpoint;

import static org.junit.Assert.assertNotNull;

import java.util.Collections;
import org.identityconnectors.framework.api.APIConfiguration;
import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.test.common.TestHelpers;
import org.junit.Test;

public class SampleConnectorTests {

    protected DormakabaConnectorConfiguration newConfiguration() {
        return new DormakabaConnectorConfiguration();
    }

    protected ConnectorFacade newFacade() {
        ConnectorFacadeFactory factory = ConnectorFacadeFactory.getInstance();
        APIConfiguration impl = TestHelpers.createTestConfiguration(DormakabaConnector.class, newConfiguration());
        impl.getResultsHandlerConfiguration().setFilteredResultsHandlerInValidationMode(true);
        return factory.newInstance(impl);
    }

    @Test
    public void basic() {
        Uid created = newFacade().create(
                ObjectClass.ACCOUNT,
                Collections.<Attribute>emptySet(),
                new OperationOptionsBuilder().build());
        assertNotNull(created);
    }
}
