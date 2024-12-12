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
import org.apache.olingo.client.api.communication.request.retrieve.ODataRetrieveRequest;
import org.apache.olingo.client.api.communication.response.ODataInvokeResponse;
import org.apache.olingo.client.api.communication.response.ODataRetrieveResponse;
import org.apache.olingo.client.api.domain.ClientEntity;
import org.apache.olingo.client.api.domain.ClientEntitySet;
import org.apache.olingo.client.api.domain.ClientValue;
import org.apache.olingo.client.api.http.HttpClientFactory;
import org.apache.olingo.client.api.uri.FilterFactory;
import org.apache.olingo.client.api.uri.URIBuilder;
import org.apache.olingo.client.api.uri.URIFilter;
import org.apache.olingo.client.core.ODataClientFactory;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpMethod;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
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
    private final SSLContext sslContext;
    private String accessToken;
    private Instant accessTokenDate;

    private final String baseCoreUri;
    private final String baseApiUri;
    private final String loginApiUri;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String username;
    private final String password;

    public DormakabaExosClient(String baseUrl, String username, String password) {
        baseCoreUri = baseUrl + "/ExosCore/api/v1.0/";
        baseApiUri = baseUrl + "/ExosApi/api/v1.0/";
        loginApiUri = baseUrl + "/ExosApiLogin/api/v1.0/login";
        this.username = username;
        this.password = password;
        client = ODataClientFactory.getClient();
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(new KeyManager[0], new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            }}, new SecureRandom());
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        final HttpRequestInterceptor tokenInterceptor = (httpRequest, httpContext) -> {
            synchronized (accessTokenRefreshLock) {
                if (accessTokenDate == null || accessToken == null ||
                        accessTokenDate.until(Instant.now(), ChronoUnit.MINUTES) >= 5) {
                    accessToken = login();
                    accessTokenDate = Instant.now();
                }
            }
            httpRequest.addHeader("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(("MyApiKey:" + accessToken).getBytes()));
        };
        client.getConfiguration().setDefaultMediaFormat(ContentType.APPLICATION_JSON);
        client.getConfiguration().setDefaultValueFormat(ContentType.APPLICATION_JSON);
        client.getConfiguration().setDefaultPubFormat(ContentType.APPLICATION_JSON);
        client.getConfiguration().setHttpClientFactory(new HttpClientFactory() {
            @Override
            public HttpClient create(HttpMethod httpMethod, URI uri) {
                return HttpClients.custom()
                        .setSSLContext(sslContext)
                        .setSSLHostnameVerifier((hostname, session) -> true)
                        .addInterceptorFirst(tokenInterceptor)
                        .build();
            }

            @Override
            public void close(HttpClient httpClient) {
                try {
                    ((CloseableHttpClient) httpClient).close();
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
        final HttpUriRequest request = new HttpPost(loginApiUri);
        request.setHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
        request.setHeader("Content-Type", "application/json");
        try (CloseableHttpClient client = HttpClients.custom()
                .setSSLContext(sslContext)
                .setSSLHostnameVerifier((hostname, session) -> true)
                .build();
             CloseableHttpResponse response = client.execute(request)) {
            final HashMap<String, Object> responseJson = objectMapper.readValue(response.getEntity().getContent(), new TypeReference<HashMap<String, Object>>() {
            });
            return (String) ((Map) responseJson.get("Value")).get("Identifier");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public Stream<AccessRight> listAccessRights(URIFilter filter, int skip, int limit) {
        URIFilter completeFilter = client.getFilterFactory().and(
                client.getFilterFactory().eq("PersonId", null),
                client.getFilterFactory().eq("IsAssignableToEmployee", true));
        if (filter != null) {
            completeFilter = client.getFilterFactory().and(filter, completeFilter);
        }
        ODataRetrieveRequest<ClientEntitySet> request = client.getRetrieveRequestFactory().getEntitySetRequest(
                client.newURIBuilder(baseApiUri)
                        .appendEntitySetSegment("accessRightAssignments")
                        .filter(completeFilter)
                        .skip(skip)
                        .top(limit)
                        .select("AccessRightId", "AccessRightName")
                        .build());
        request.setAccept(ContentType.APPLICATION_JSON.toContentTypeString());
        request.setContentType(ContentType.APPLICATION_JSON.toContentTypeString());
        ODataRetrieveResponse<ClientEntitySet> response = request.execute();
        return response.getBody().getEntities().stream().map(clientEntity -> {
            try {
                return new AccessRight(clientEntity.getProperty("AccessRightId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("AccessRightName").getValue().asPrimitive().toCastValue(String.class));
            } catch (EdmPrimitiveTypeException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<AccessRightAssignment> listAssignedAccessRights(String personId, int skip, int limit) {
        ODataRetrieveRequest<ClientEntitySet> request = client.getRetrieveRequestFactory().getEntitySetRequest(client.newURIBuilder(baseApiUri)
                .appendEntitySetSegment("accessRightAssignments")
                .addCustomQueryOption("personId", personId)
                .skip(skip)
                .top(limit)
                .filter(client.getFilterFactory().eq("PersonId", personId))
                .select("AssignmentId", "AccessRightId", "Commentary", "TimeZoneId")
                .build());
        request.setAccept(ContentType.APPLICATION_JSON.toContentTypeString());
        request.setContentType(ContentType.APPLICATION_JSON.toContentTypeString());
        ODataRetrieveResponse<ClientEntitySet> response = request.execute();
        return response.getBody().getEntities().stream().map(clientEntity -> {
            try {
                return new AccessRightAssignment(
                        clientEntity.getProperty("AssignmentId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("AccessRightId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("Commentary").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("TimeZoneId").getValue().asPrimitive().toCastValue(String.class)
                );
            } catch (EdmPrimitiveTypeException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    public Stream<Employee> listEmployees(URIFilter filter, boolean includeAccessRights, int skip, int limit) {
        URIFilter completeFilter = client.getFilterFactory().eq("CategoryText", "Studierende");
        if (filter != null) {
            completeFilter = client.getFilterFactory().and(filter, completeFilter);
        }
        URIBuilder uriBuilder = client.newURIBuilder(baseCoreUri)
                .appendEntitySetSegment("staff")
                .appendEntitySetSegment("employees")
                .filter(completeFilter)
                .skip(skip)
                .top(limit)
                .select("PersonId", "FullName");
        if (includeAccessRights) {
            uriBuilder = uriBuilder.expandWithSelect("AccessRights", "AccessRightId");
        }
        ODataRetrieveResponse<ClientEntitySet> response = client.getRetrieveRequestFactory().getEntitySetRequest(uriBuilder.build())
                .execute();
        return response.getBody().getEntities().stream().map(clientEntity -> {
            try {
                return new Employee(clientEntity.getProperty("PersonId").getValue().asPrimitive().toCastValue(String.class),
                        clientEntity.getProperty("FullName").getValue().asPrimitive().toCastValue(String.class),
                        includeAccessRights ? clientEntity.getProperty("AccessRights").getValue().asCollection().asJavaCollection().stream().map((Object value) -> {
                            final HashMap<String, String> accessRight = ((HashMap) value);
                            return accessRight.get("AccessRightId");
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

        ODataInvokeResponse<ClientEntity> response =
                client.getInvokeRequestFactory().getActionInvokeRequest(client.newURIBuilder(baseApiUri)
                        .appendEntitySetSegment("persons")
                        .appendEntitySetSegment(personId)
                        .appendActionCallSegment("assignAccessRight")
                        .build(), ClientEntity.class, arguments
                ).execute();
    }

    void removeAccessRightToPerson(String personId, String assignmentId) {
        ODataInvokeResponse<ClientEntity> response = client.getInvokeRequestFactory().getActionInvokeRequest(client.newURIBuilder(baseApiUri)
                .appendEntitySetSegment("persons")
                .appendEntitySetSegment(personId)
                .appendActionCallSegment("unassignAccessRight")
                .appendEntitySetSegment(assignmentId)
                .build(), ClientEntity.class
        ).execute();
    }


    public void updateAccessRightsOfPerson(String personId, List<String> accessRightIdsToAdd, List<String> accessRightIdsToRemove) {
        final List<AccessRightAssignment> existingAccessRightAssignments = listAssignedAccessRights(personId, 0, 100);
        List<String> assigmentIdsToRemove = accessRightIdsToRemove.stream()
                .map(accessRightIdToRemove ->
                        existingAccessRightAssignments.stream()
                                .filter(accessRightAssignment -> accessRightAssignment.getAccessRightId().equals(accessRightIdToRemove) &&
                                        ASSIGNMENT_FROM_MIDPOINT_MARKER.equals(accessRightAssignment.getComment()))
                                .findFirst()
                                .orElse(null)
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
        final List<AccessRightAssignment> existingAccessRightAssignments = listAssignedAccessRights(personId, 0, 100);
        List<String> assigmentIdsToRemove = existingAccessRightAssignments.stream()
                .filter(accessRightAssignment ->
                        !accessRightIdsToReplace.contains(accessRightAssignment.getAccessRightId()) &&
                                ASSIGNMENT_FROM_MIDPOINT_MARKER.equals(accessRightAssignment.getComment()))
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
        private final List<String> assignedAccessRightIds;

        public Employee(String personId, String matrikelNumber, List<String> assignedAccessRightIds) {
            this.personId = personId;
            this.matrikelNumber = matrikelNumber;
            this.assignedAccessRightIds = assignedAccessRightIds;
        }

        public String getPersonId() {
            return personId;
        }

        public String getMatrikelNumber() {
            return matrikelNumber;
        }

        public List<String> getAssignedAccessRightIds() {
            return assignedAccessRightIds;
        }
    }
}
