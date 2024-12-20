package de.stuvus.connid.dormakaba_midpoint;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.olingo.client.api.uri.URIFilter;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.*;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.operations.*;

@ConnectorClass(configurationClass = DormakabaConnectorConfiguration.class,
        displayNameKey = "dormakaba.connector.display")
public class DormakabaConnector implements Connector,
        UpdateDeltaOp, SchemaOp, TestOp, SearchOp<URIFilter> {

    private static final Log LOG = Log.getLog(DormakabaConnector.class);
    public static final String ATTRIBUTE_MATNR_NAME = "matrikelNummer";
    public static final ObjectClass OBJECT_CLASS_DOORACCESSRIGHT = new ObjectClass("DoorAccessRight");
    public static final String ATTRIBUTE_ACCESSRIGHTS_NAME = "accessRights";

    private DormakabaConnectorConfiguration configuration;
    private DormakabaExosClient exosClient;


    @Override
    public DormakabaConnectorConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(final Configuration configuration) {
        DormakabaConnectorConfiguration config = (DormakabaConnectorConfiguration) configuration;
        this.configuration = config;
        this.exosClient = new DormakabaExosClient(config.getExosEndpointProperty(), config.getExosUsernameProperty(), config.getExosPasswordProperty());
        LOG.ok("Connector {0} successfully inited", getClass().getName());
    }

    @Override
    public void dispose() {
        this.exosClient = null;
    }

    @Override
    public Schema schema() {
        final SchemaBuilder schemaBuilder = new SchemaBuilder(DormakabaConnector.class);

        schemaBuilder.defineObjectClass(
                new ObjectClassInfoBuilder()
                        .setType(ObjectClass.ACCOUNT_NAME)
                        .addAttributeInfo(Name.INFO)
                        .addAttributeInfo(new AttributeInfoBuilder(Uid.NAME)
                                .setType(String.class)
                                .setRequired(true)
                                .setCreateable(false)
                                .setUpdateable(false)
                                .setReadable(true)
                                .build())
                        .addAttributeInfo(new AttributeInfoBuilder(ATTRIBUTE_MATNR_NAME)
                                .setType(String.class)
                                .setRequired(false)
                                .setCreateable(false)
                                .setUpdateable(false)
                                .setReadable(true)
                                .build())
                        .addAttributeInfo(new AttributeInfoBuilder(ATTRIBUTE_ACCESSRIGHTS_NAME, String.class)
                                .setCreateable(false)
                                .setUpdateable(true)
                                .setReadable(true)
                                .setReturnedByDefault(false)
                                .setMultiValued(true)
                                .build())
                        .build());

        schemaBuilder.defineObjectClass(
                new ObjectClassInfoBuilder()
                        .setType(OBJECT_CLASS_DOORACCESSRIGHT.getObjectClassValue())
                        .addAttributeInfo(Name.INFO)
                        .addAttributeInfo(new AttributeInfoBuilder(Uid.NAME)
                                .setType(String.class)
                                .setRequired(true)
                                .setCreateable(false)
                                .setUpdateable(false)
                                .setReadable(true)
                                .build())
                        .build());
        return schemaBuilder.build();
    }

    @Override
    public void test() {
    }

    @Override
    public FilterTranslator<URIFilter> createFilterTranslator(
            final ObjectClass objectClass,
            final OperationOptions options) {

        return new AbstractFilterTranslator<>() {
            protected URIFilter createAndExpression(URIFilter leftExpression, URIFilter rightExpression) {
                return exosClient.filterFactory().and(leftExpression, rightExpression);
            }

            protected URIFilter createOrExpression(URIFilter leftExpression, URIFilter rightExpression) {
                return exosClient.filterFactory().or(leftExpression, rightExpression);
            }

            protected URIFilter createEqualsExpression(EqualsFilter filter, boolean not) {
                if (filter.getAttribute().getValue().size() != 1) return null;
                String fieldName;

                String name = filter.getName();
                if (name.equals(ATTRIBUTE_MATNR_NAME)) {
                    fieldName = "FullName";
                } else if (name.equals(Uid.NAME) && objectClass.equals(ObjectClass.ACCOUNT)) {
                    fieldName = "PersonId";
                } else if (name.equals(Uid.NAME) && objectClass.equals(OBJECT_CLASS_DOORACCESSRIGHT)) {
                    fieldName = "AccessRightId";
                } else {
                    return null;
                }

                if (not) {
                    return exosClient.filterFactory().ne(fieldName, filter.getAttribute().getValue().get(0));
                } else {
                    return exosClient.filterFactory().eq(fieldName, filter.getAttribute().getValue().get(0));
                }
            }
        };
    }

    private void paginatedHandler(BiFunction<Integer, Integer, Stream<ConnectorObject>> fetchFunction,
                                  ResultsHandler resultsHandler,
                                  Integer pageSize,
                                  Integer pageOffset) {

        if (pageSize == null) {
            pageSize = 80;
        }
        if (pageOffset == null) {
            pageOffset = 0;
        }
        AtomicBoolean stopped = new AtomicBoolean(false);

        while (!stopped.get()) {
            fetchFunction.apply(pageSize, pageOffset)
                    .takeWhile(connectorObject -> !stopped.get())
                    .forEach(connectorObject -> stopped.set(!resultsHandler.handle(connectorObject)));
            pageOffset += pageSize;
        }
    }

    @Override
    public void executeQuery(
            final ObjectClass objectClass,
            final URIFilter query,
            final ResultsHandler handler,
            final OperationOptions options) {
        Integer pageSize = options.getPageSize();
        Integer offset = options.getPagedResultsOffset();

        if (objectClass.equals(ObjectClass.ACCOUNT) || objectClass.equals(ObjectClass.ALL)) {
            boolean includeAccessRights;
            if (options.getAttributesToGet() != null) {
                includeAccessRights = Arrays.asList(options.getAttributesToGet()).contains(ATTRIBUTE_ACCESSRIGHTS_NAME);
            } else {
                includeAccessRights = true;
            }

            paginatedHandler(
                    (Integer pSize, Integer pageOffset) -> exosClient.listEmployees(query, includeAccessRights, pageOffset, pSize)
                            .map(employee -> {
                                final HashSet<Attribute> attributes = new HashSet<>();
                                attributes.add(AttributeBuilder.build(Uid.NAME, employee.getPersonId()));
                                attributes.add(new Name(employee.getPersonId()));
                                attributes.add(AttributeBuilder.build(ATTRIBUTE_MATNR_NAME, employee.getMatrikelNumber()));
                                if (includeAccessRights) {
                                    attributes.add(AttributeBuilder.build(ATTRIBUTE_ACCESSRIGHTS_NAME, employee.getAssignedAccessRightIds()));
                                }

                                return new ConnectorObject(OBJECT_CLASS_DOORACCESSRIGHT, attributes);
                            }),
                    handler, pageSize, offset);
        }
        if (objectClass.equals(OBJECT_CLASS_DOORACCESSRIGHT) || objectClass.equals(ObjectClass.ALL)) {
            paginatedHandler(
                    (Integer pSize, Integer pageOffset) -> exosClient.listAccessRights(query, pageOffset, pSize)
                            .map(accessRight -> {
                                final HashSet<Attribute> attributes = new HashSet<>();
                                attributes.add(AttributeBuilder.build(Uid.NAME, accessRight.getAccessRightId()));
                                attributes.add(new Name(accessRight.getName()));
                                return new ConnectorObject(OBJECT_CLASS_DOORACCESSRIGHT, attributes);
                            }),
                    handler, pageSize, offset);
        }
    }

    @Override
    public Set<AttributeDelta> updateDelta(ObjectClass objectClass, Uid uid, Set<AttributeDelta> set, OperationOptions operationOptions) {
        System.out.println("Update Delta");
        System.out.println(objectClass);
        System.out.println(uid);
        System.out.println(set);
        if (!objectClass.equals(ObjectClass.ACCOUNT))
            throw new InvalidAttributeValueException();
        if (set.size() > 1)
            throw new InvalidAttributeValueException();
        AttributeDelta accessRightDelta = set.iterator().next();
        if (!accessRightDelta.getName().equals(ATTRIBUTE_ACCESSRIGHTS_NAME))
            throw new InvalidAttributeValueException();

        String personId = uid.getUidValue();
        if (accessRightDelta.getValuesToReplace() != null) {
            exosClient.replaceAccessRightsOfPerson(personId,
                    accessRightDelta.getValuesToReplace().stream().map(o -> (String) o).collect(Collectors.toList()));
        } else {
            List<String> valuesToAdd = (accessRightDelta.getValuesToAdd() == null) ? Collections.emptyList() : accessRightDelta.getValuesToAdd().stream().map(o -> (String) o).collect(Collectors.toList());
            List<String> valuesToRemove = (accessRightDelta.getValuesToRemove() == null) ? Collections.emptyList() : accessRightDelta.getValuesToRemove().stream().map(o -> (String) o).collect(Collectors.toList());
            exosClient.updateAccessRightsOfPerson(personId, valuesToAdd, valuesToRemove);
        }

        return Collections.emptySet();
    }
}
