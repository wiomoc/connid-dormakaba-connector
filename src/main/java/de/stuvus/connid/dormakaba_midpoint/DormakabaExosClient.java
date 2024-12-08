package de.stuvus.connid.dormakaba_midpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.olingo.client.api.ODataClient;
import org.apache.olingo.client.api.communication.response.ODataInvokeResponse;
import org.apache.olingo.client.api.communication.response.ODataRetrieveResponse;
import org.apache.olingo.client.api.domain.ClientComplexValue;
import org.apache.olingo.client.api.domain.ClientEntity;
import org.apache.olingo.client.api.domain.ClientEntitySet;
import org.apache.olingo.client.api.domain.ClientValue;
import org.apache.olingo.client.api.http.HttpClientFactory;
import org.apache.olingo.client.api.uri.FilterFactory;
import org.apache.olingo.client.api.uri.URIBuilder;
import org.apache.olingo.client.api.uri.URIFilter;
import org.apache.olingo.client.core.ODataClientFactory;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.identityconnectors.framework.common.exceptions.UnknownUidException;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DormakabaExosClient {
    private final ODataClient client;

    public static final String ASSIGNMENT_FROM_MIDPOINT_MARKER = "__midpoint-automation__";
    public static final String ALWAYS_TIME_ZONE = "0E4E42B9-160B-45AD-AC71-C5518775140F"; // Zeitzone für immer, einzig sinnvolle zu verwenden


    private final Object accessTokenRefreshLock = new Object();
    private String accessToken;
    private Instant accessTokenDate;

    private final String baseApiUri;
    private final String baseLoginApiUri;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String username;
    private String password;

    public DormakabaExosClient(String baseUrl, String username, String password) {
        baseApiUri = baseUrl + "/ExosCore/api/v1.0/";
        baseLoginApiUri = baseUrl + "/ExosApiLogin/api/v1.0/";
        this.username = username;
        this.password = password;
        client = ODataClientFactory.getClient();
        final HttpRequestInterceptor tokenInterceptor = (httpRequest, httpContext) -> {
            synchronized (accessTokenRefreshLock) {
                if (accessTokenDate.until(Instant.now(), ChronoUnit.MINUTES) >= 5) {
                    accessToken = login();
                    accessTokenDate = Instant.now();
                }
            }
            httpRequest.addHeader("Authorization", "Basic " + accessToken);
        };
        client.getConfiguration().setHttpClientFactory(new HttpClientFactory() {
            @Override
            public HttpClient create(HttpMethod httpMethod, URI uri) {
                return HttpClients.custom().addInterceptorFirst(tokenInterceptor).build();
            }

            @Override
            public void close(HttpClient httpClient) {
                try {
                    ((CloseableHttpClient) client).close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public FilterFactory filterFactory() {
        return client.getFilterFactory();
    }

    private String login() {
        final HttpUriRequest request = new HttpPost(baseLoginApiUri);
        request.setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
        try (CloseableHttpClient client = HttpClients.createMinimal(); CloseableHttpResponse response = client.execute(request)) {
            final HashMap<String,Object> responseJson = objectMapper.readValue(response.getEntity().getContent(), new TypeReference<HashMap<String,Object>>() {});
            return (String) ((Map)responseJson.get("value")).get("Identifier");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public Stream<AccessRight> listAccessRights(URIFilter filter, int skip, int limit) {
        ODataRetrieveResponse<ClientEntitySet> response = client.getRetrieveRequestFactory().getEntitySetRequest(client.newURIBuilder(baseApiUri)
                        .appendEntitySetSegment("accessRightAssignments")
                        .filter(client.getFilterFactory().and(filter, client.getFilterFactory().eq("PersonId", null)))
                        .skip(skip)
                        .top(limit)
                        .select("AccessRightId", "AccessRightName")
                        .build())
                .execute();
        return response.getBody().getEntities().stream().map(clientEntity -> {
            try {
                return new AccessRight(clientEntity.getProperty("AccessRightId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("AccessRightName").getValue().asPrimitive().toCastValue(String.class));
            } catch (EdmPrimitiveTypeException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Stream<Employee> listEmployees(URIFilter filter, boolean includeAccessRights, int skip, int limit) {
        URIBuilder uriBuilder = client.newURIBuilder(baseApiUri)
                .appendEntitySetSegment("staff")
                .appendEntitySetSegment("employees")
                .filter(client.getFilterFactory().and(filter, client.getFilterFactory().eq("CategoryText", "Studierende")))
                .skip(skip)
                .top(limit)
                .select("PersonId", "FullName");
        if (includeAccessRights) {
            uriBuilder = uriBuilder.expandWithSelect("AccessRights", "AssignmentId", "AccessRightId", "Comment", "TimeZoneId");
        }
        ODataRetrieveResponse<ClientEntitySet> response = client.getRetrieveRequestFactory().getEntitySetRequest(uriBuilder.build())
                .execute();
        return response.getBody().getEntities().stream().map(clientEntity -> {
            try {
                return new Employee(clientEntity.getProperty("PersonId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("FullName").getValue().asPrimitive().toCastValue(String.class),
                        includeAccessRights ? clientEntity.getProperty("AccessRights").getValue().asCollection().asJavaCollection().stream().map((Object value) -> {
                            final ClientComplexValue accessRight = ((ClientValue) value).asComplex();
                            try {
                                return new AccessRightAssignment(
                                        accessRight.get("AssignmentId").getValue().asPrimitive().toCastValue(String.class),
                                        accessRight.get("AccessRightId").getValue().asPrimitive().toCastValue(String.class),
                                        accessRight.get("Comment").getValue().asPrimitive().toCastValue(String.class),
                                        accessRight.get("TimeZoneId").getValue().asPrimitive().toCastValue(String.class)
                                );
                            } catch (EdmPrimitiveTypeException e) {
                                throw new RuntimeException(e);
                            }
                        }).collect(Collectors.toList()) : null
                );
            } catch (EdmPrimitiveTypeException e) {
                throw new RuntimeException(e);
            }
        });
    }


    void addAccessRightToPerson(String personId, String accessRightId) {
        Map<String, ClientValue> arguments = new HashMap<>();
        arguments.put("AccessRightId", client.getObjectFactory().newPrimitiveValueBuilder().buildString(accessRightId));
        arguments.put("Comment", client.getObjectFactory().newPrimitiveValueBuilder().buildString(ASSIGNMENT_FROM_MIDPOINT_MARKER));
        arguments.put("IsOfficeModeEnabled", client.getObjectFactory().newPrimitiveValueBuilder().buildBoolean(false));
        arguments.put("TimeZoneId", client.getObjectFactory().newPrimitiveValueBuilder().buildString(ALWAYS_TIME_ZONE));

        ODataInvokeResponse<ClientEntity> response = client.getInvokeRequestFactory().getActionInvokeRequest(client.newURIBuilder(baseApiUri)
                .appendEntitySetSegment("persons")
                .appendEntityIdSegment(personId)
                .appendOperationCallSegment("assignAccessRight")
                .build(), ClientEntity.class, arguments
        ).execute();
    }

    void removeAccessRightToPerson(String personId, String accessRightId) {
        ODataInvokeResponse<ClientEntity> response = client.getInvokeRequestFactory().getActionInvokeRequest(client.newURIBuilder(baseApiUri)
                .appendEntitySetSegment("persons")
                .appendEntityIdSegment(personId)
                .appendOperationCallSegment("unassignAccessRight")
                .appendEntityIdSegment("accessRightId")
                .build(), ClientEntity.class
        ).execute();
    }


    public void updateAccessRightsOfPerson(String personId, List<String> accessRightIdsToAdd, List<String> accessRightIdsToRemove) {
        final Employee employee = listEmployees(client.getFilterFactory().eq("PersonId", personId), true, 0, 1).findFirst().orElseThrow(UnknownUidException::new);
        final List<AccessRightAssignment> existingAccessRightAssignments = employee.getAccessRightAssigments();
        List<String> assigmentIdsToRemove = accessRightIdsToRemove.stream()
                .map(accessRightIdToRemove ->
                        existingAccessRightAssignments.stream().filter(accessRightAssignment -> accessRightAssignment.getAccessRightId().equals(accessRightIdToRemove) &&
                                accessRightAssignment.getComment().equals(ASSIGNMENT_FROM_MIDPOINT_MARKER)).findFirst().orElse(null)
                )
                .filter(Objects::nonNull)
                .map(AccessRightAssignment::getAssignmentId)
                .collect(Collectors.toList());


        List<String> filteredAccessRightIdsToAdd = accessRightIdsToAdd.stream().filter(accessRightIdToAdd ->
                existingAccessRightAssignments.stream().noneMatch(accessRightAssignment -> accessRightAssignment.getAccessRightId().equals(accessRightIdToAdd))
        ).collect(Collectors.toList());

        for (String assigmentIdToRemove : assigmentIdsToRemove) {
            removeAccessRightToPerson(personId, assigmentIdToRemove);
        }

        for (String accessRightIdToAdd : filteredAccessRightIdsToAdd) {
            addAccessRightToPerson(personId, accessRightIdToAdd);
        }
    }

    public void replaceAccessRightsOfPerson(String personId, List<String> accessRightIdsToReplace) {
        final Employee employee = listEmployees(client.getFilterFactory().eq("PersonId", personId), true, 0, 1).findFirst().orElseThrow(UnknownUidException::new);
        final List<AccessRightAssignment> existingAccessRightAssignments = employee.getAccessRightAssigments();
        List<String> assigmentIdsToRemove = existingAccessRightAssignments.stream()
                .filter(accessRightAssignment -> !accessRightIdsToReplace.contains(accessRightAssignment.getAccessRightId()) && accessRightAssignment.getComment().equals(ASSIGNMENT_FROM_MIDPOINT_MARKER))
                .map(AccessRightAssignment::getAssignmentId)
                .collect(Collectors.toList());

        List<String> filteredAccessRightIdsToAdd = accessRightIdsToReplace.stream().filter(accessRightIdToAdd ->
                existingAccessRightAssignments.stream().noneMatch(accessRightAssignment -> accessRightAssignment.accessRightId.equals(accessRightIdToAdd))
        ).collect(Collectors.toList());

        for (String assigmentIdToRemove : assigmentIdsToRemove) {
            removeAccessRightToPerson(personId, assigmentIdToRemove);
        }

        for (String accessRightIdToAdd : filteredAccessRightIdsToAdd) {
            addAccessRightToPerson(personId, accessRightIdToAdd);
        }
    }


    public static class AccessRight {
        private final String accessRightId;
        private final String name;

        public AccessRight(String id, String name) {
            this.accessRightId = id;
            this.name = name;
        }

        public String getAccessRightId() {
            return accessRightId;
        }

        public String getName() {
            return name;
        }
    }

    public static class AccessRightAssignment {
        private final String assignmentId;
        private final String accessRightId;
        private final String comment;
        private final String timeZoneId;

        public AccessRightAssignment(String assignmentId, String accessRightId, String comment, String timeZoneId) {
            this.assignmentId = assignmentId;
            this.accessRightId = accessRightId;
            this.comment = comment;
            this.timeZoneId = timeZoneId;
        }

        public String getAssignmentId() {
            return assignmentId;
        }

        public String getAccessRightId() {
            return accessRightId;
        }

        public String getComment() {
            return comment;
        }

        public String getTimeZoneId() {
            return timeZoneId;
        }
    }

    public static class Employee {
        private final String personId;
        private final String matrikelNumber;
        private final List<AccessRightAssignment> accessRights;

        public Employee(String personId, String matrikelNumber, List<AccessRightAssignment> accessRights) {
            this.personId = personId;
            this.matrikelNumber = matrikelNumber;
            this.accessRights = accessRights;
        }

        public String getPersonId() {
            return personId;
        }

        public String getMatrikelNumber() {
            return matrikelNumber;
        }

        public List<AccessRightAssignment> getAccessRightAssigments() {
            return accessRights;
        }
    }
}
