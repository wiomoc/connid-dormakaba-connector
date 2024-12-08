package de.stuvus.connid.dormakaba_midpoint;

import org.identityconnectors.common.StringUtil;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

public class DormakabaConnectorConfiguration extends AbstractConfiguration {

    private String exosEndpointProperty = "";
    private String exosUsernameProperty = "";
    private String exosPasswordProperty = "";

    @ConfigurationProperty(displayMessageKey = "property.exosEndpoint.display",
            helpMessageKey = "property.exosEndpoint.help", order = 1)
    public String getExosEndpointProperty() {
        return exosEndpointProperty;
    }


    public void setExosEndpointProperty(String exosEndpointProperty) {
        this.exosEndpointProperty = exosEndpointProperty;
    }


    @ConfigurationProperty(displayMessageKey = "property.exosUsername.display",
            helpMessageKey = "property.exosUsername.help", order = 1)

    public String getExosUsernameProperty() {
        return exosUsernameProperty;
    }

    public void setExosUsernameProperty(String exosUsernameProperty) {
        this.exosUsernameProperty = exosUsernameProperty;
    }


    @ConfigurationProperty(displayMessageKey = "property.exosPassword.display",
            helpMessageKey = "property.exosPassword.help", order = 1)
    public String getExosPasswordProperty() {
        return exosPasswordProperty;
    }

    public void setExosPasswordProperty(String exosPasswordProperty) {
        this.exosPasswordProperty = exosPasswordProperty;
    }

    @Override
    public void validate() {
        if (StringUtil.isBlank(exosEndpointProperty)) {
            throw new ConfigurationException("exosEndpointProperty must not be blank!");
        }

        if (StringUtil.isBlank(exosUsernameProperty)) {
            throw new ConfigurationException("exosUsernameProperty must not be blank!");
        }

        if (StringUtil.isBlank(exosPasswordProperty)) {
            throw new ConfigurationException("exosPasswordProperty must not be blank!");
        }
    }

}
