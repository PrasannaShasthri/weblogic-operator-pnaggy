// Copyright 2017, 2018, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at
// http://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.rest;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1TokenReviewStatus;
import io.kubernetes.client.models.V1UserInfo;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import oracle.kubernetes.operator.StartupControlConstants;
import oracle.kubernetes.operator.helpers.AuthenticationProxy;
import oracle.kubernetes.operator.helpers.AuthorizationProxy;
import oracle.kubernetes.operator.helpers.AuthorizationProxy.Operation;
import oracle.kubernetes.operator.helpers.AuthorizationProxy.Resource;
import oracle.kubernetes.operator.helpers.AuthorizationProxy.Scope;
import oracle.kubernetes.operator.helpers.CallBuilderFactory;
import oracle.kubernetes.operator.helpers.ClusterConfig;
import oracle.kubernetes.operator.helpers.DomainConfig;
import oracle.kubernetes.operator.helpers.LegalNames;
import oracle.kubernetes.operator.helpers.LifeCycleHelper;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.logging.MessageKeys;
import oracle.kubernetes.operator.rest.backend.RestBackend;
import oracle.kubernetes.operator.wlsconfig.WlsClusterConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.wlsconfig.WlsRetriever;
import oracle.kubernetes.operator.work.ContainerResolver;
import oracle.kubernetes.weblogic.domain.v1.Domain;
import oracle.kubernetes.weblogic.domain.v1.DomainList;
import oracle.kubernetes.weblogic.domain.v1.DomainSpec;

/**
 * RestBackendImpl implements the backend of the WebLogic operator REST api by making calls to
 * Kubernetes and WebLogic. A separate instance is created for each REST request since we need to
 * hold some per-request state.
 */
public class RestBackendImpl implements RestBackend {

  private V1UserInfo userInfo;

  private final AuthenticationProxy atn = new AuthenticationProxy();
  private final AuthorizationProxy atz = new AuthorizationProxy();
  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private final String principal;
  private final Collection<String> targetNamespaces;

  /**
   * Construct a RestBackendImpl that is used to handle one WebLogic operator REST request.
   *
   * @param principal is the name of the Kubernetes user to use when calling the Kubernetes REST
   *     api.
   * @param accessToken is the access token of the Kubernetes service account of the client calling
   *     the WebLogic operator REST api.
   * @param targetNamespaces a list of Kubernetes namepaces that contain domains that the WebLogic
   *     operator manages.
   */
  public RestBackendImpl(
      String principal, String accessToken, Collection<String> targetNamespaces) {
    LOGGER.entering(principal, targetNamespaces);
    this.principal = principal;
    userInfo = authenticate(accessToken);
    this.targetNamespaces = targetNamespaces;
    LOGGER.exiting();
  }

  protected void authorize(String domainUID, String cluster, Operation operation) {
    // TBD - should cluster atz be different than domain atz?
    authorize(domainUID, operation);
  }

  protected void authorize(String domainUID, Operation operation) {
    LOGGER.entering(domainUID, operation);
    boolean authorized = false;
    if (domainUID == null) {
      authorized =
          atz.check(
              userInfo.getUsername(),
              userInfo.getGroups(),
              operation,
              Resource.DOMAINS,
              null,
              Scope.cluster,
              null);
    } else {
      authorized =
          atz.check(
              userInfo.getUsername(),
              userInfo.getGroups(),
              operation,
              Resource.DOMAINS,
              domainUID,
              Scope.namespace,
              getNamespace(domainUID));
    }
    if (authorized) {
      LOGGER.exiting();
      return;
    }
    // TBD - should we say what who the user is and what the user can't do?
    WebApplicationException e = createWebApplicationException(Status.FORBIDDEN, null);
    LOGGER.throwing(e);
    throw e;
  }

  private String getNamespace(String domainUID) {
    if (domainUID == null) {
      throw new AssertionError(formatMessage(MessageKeys.NULL_DOMAIN_UID));
    }

    return getNamespace(domainUID, getDomainsList());
  }

  private String getNamespace(String domainUID, List<Domain> domains) {
    if (domainUID == null) {
      throw new AssertionError(formatMessage(MessageKeys.NULL_DOMAIN_UID));
    }
    Domain domain = findDomain(domainUID, domains);
    return domain.getMetadata().getNamespace();
  }

  protected V1UserInfo authenticate(String accessToken) {
    LOGGER.entering();
    V1TokenReviewStatus status = atn.check(principal, accessToken);
    if (status == null) {
      throw new AssertionError(formatMessage(MessageKeys.NULL_TOKEN_REVIEW_STATUS));
    }
    String error = status.getError();
    if (error != null) {
      WebApplicationException e = createWebApplicationException(Status.UNAUTHORIZED, error);
      LOGGER.throwing(e);
      throw e;
    }
    if (!status.isAuthenticated()) {
      // don't know why the user didn't get authenticated
      WebApplicationException e = createWebApplicationException(Status.UNAUTHORIZED, null);
      LOGGER.throwing(e);
      throw e;
    }
    userInfo = status.getUser();
    if (userInfo == null) {
      throw new AssertionError(formatMessage(MessageKeys.NULL_USER_INFO, status));
    }
    LOGGER.exiting(userInfo);
    return userInfo;
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> getDomainUIDs() {
    LOGGER.entering();
    authorize(null, Operation.list);
    Set<String> result = new TreeSet<>();
    List<Domain> domains = getDomainsList();
    for (Domain domain : domains) {
      result.add(domain.getSpec().getDomainUID());
    }
    LOGGER.exiting(result);
    return result;
  }

  protected List<Domain> getDomainsList() {
    CallBuilderFactory factory = getCallBuilderFactory();
    Collection<List<Domain>> c = new ArrayList<List<Domain>>();
    try {
      for (String ns : targetNamespaces) {
        DomainList dl = factory.create().listDomain(ns);

        if (dl != null) {
          c.add(dl.getItems());
        }
      }
      return c.stream().flatMap(Collection::stream).collect(Collectors.toList());
    } catch (ApiException e) {
      throw handleApiException(e);
    }
  }

  protected CallBuilderFactory getCallBuilderFactory() {
    return ContainerResolver.getInstance().getContainer().getSPI(CallBuilderFactory.class);
  }

  /** {@inheritDoc} */
  @Override
  public boolean isDomainUID(String domainUID) {
    LOGGER.entering(domainUID);
    boolean result = false;
    authorize(null, Operation.list);
    result = getDomainUIDs().contains(domainUID);
    LOGGER.exiting(result);
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public Set<String> getClusters(String domainUID) {
    LOGGER.entering(domainUID);
    if (!isDomainUID(domainUID)) {
      throw new AssertionError(formatMessage(MessageKeys.INVALID_DOMAIN_UID, domainUID));
    }
    authorize(domainUID, Operation.get);

    // Get list of WLS Configured Clusters defined for the corresponding WLS Domain identified by
    // Domain UID
    Domain domain = findDomain(domainUID);
    String namespace = getNamespace(domainUID);
    String adminServerServiceName = getAdminServerServiceName(domain.getSpec());
    String adminSecretName = getAdminServiceSecretName(domain);
    Map<String, WlsClusterConfig> wlsClusterConfigs =
        getWLSConfiguredClusters(namespace, adminServerServiceName, adminSecretName);
    Set<String> result = wlsClusterConfigs.keySet();
    LOGGER.exiting(result);
    return result;
  }

  private static String getAdminServerServiceName(DomainSpec domainSpec) {
    return LegalNames.toServerServiceName(domainSpec.getDomainUID(), domainSpec.getAsName());
  }

  /** {@inheritDoc} */
  @Override
  public boolean isCluster(String domainUID, String cluster) {
    LOGGER.entering(domainUID, cluster);
    authorize(domainUID, cluster, Operation.list);
    boolean result = getClusters(domainUID).contains(cluster);
    LOGGER.exiting(result);
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public void scaleCluster(String domainUID, String cluster, int managedServerCount) {
    LOGGER.entering(domainUID, cluster, managedServerCount);

    if (managedServerCount < 0) {
      throw createWebApplicationException(
          Status.BAD_REQUEST, MessageKeys.INVALID_MANAGE_SERVER_COUNT, managedServerCount);
    }

    authorize(domainUID, cluster, Operation.update);

    List<Domain> domains = getDomainsList();
    Domain domain = findDomain(domainUID, domains);

    String namespace = getNamespace(domainUID, domains);

    verifyWLSConfiguredClusterCapacity(namespace, domain, cluster, managedServerCount);

    updateReplicasForDomain(namespace, domain, cluster, managedServerCount);
    LOGGER.exiting();
  }

  private void updateReplicasForDomain(
      String namespace, Domain domain, String cluster, int managedServerCount) {
    // Capacity of configured cluster is valid for scaling
    // Set replicas value on corresponding ClusterStartup (if defined)
    // or on the Domain level replicas value for cluster not defined in a ClusterStartup

    boolean domainModified = isReplicaCountUpdated(namespace, domain, cluster, managedServerCount);

    LOGGER.finer("domainModified: " + domainModified);
    if (domainModified) {
      replaceDomain(namespace, domain, domain.getSpec().getDomainUID());
    }
  }

  protected boolean isReplicaCountUpdated(
      String namespace, Domain domain, String cluster, int managedServerCount) {
    boolean domainModified = false;
    ClusterConfig clusterConfig = getClusterConfig(domain, namespace, cluster);

    if (managedServerCount != clusterConfig.getReplicas()) {
      clusterConfig.setReplicas(managedServerCount);
      LifeCycleHelper.instance().updateDomainSpec(domain, clusterConfig);
      String startupControl = domain.getSpec().getStartupControl();
      // startupControl is not defined then we're using new Lifecycle configuration
      if (startupControl != null
          && !StartupControlConstants.AUTO_STARTUPCONTROL.equals(startupControl)) {
        // WebLogic Cluster is not defined in ClusterStartup AND Startup Control is not spec'd as
        // AUTO
        // so scaling will not occur since Domain.spec.Replicas property will be ignored.
        throw createWebApplicationException(
            Status.BAD_REQUEST, MessageKeys.SCALING_AUTO_CONTROL_AUTO, cluster);
      }
      domainModified = true;
    }
    return domainModified;
  }

  protected void replaceDomain(String namespace, Domain domain, String domainUID) {
    try {
      CallBuilderFactory factory = getCallBuilderFactory();
      // Write out the Domain with updated replica values
      // TODO: Can we patch instead of replace?
      factory.create().replaceDomain(domainUID, namespace, domain);
    } catch (ApiException e) {
      LOGGER.finer(
          "Unexpected exception when updating Domain " + domainUID + " in namespace " + namespace,
          e);
      throw new WebApplicationException(e.getMessage());
    }
  }

  protected void verifyWLSConfiguredClusterCapacity(
      String namespace, Domain domain, String cluster, int managedServerCount) {
    // Query WebLogic Admin Server for current configured WebLogic Cluster size
    // and verify we have enough configured managed servers to auto-scale
    String adminServerServiceName = getAdminServerServiceName(domain.getSpec());
    String adminSecretName = getAdminServiceSecretName(domain);
    WlsClusterConfig wlsClusterConfig =
        getWlsClusterConfig(namespace, cluster, adminServerServiceName, adminSecretName);

    // Verify the current configured cluster size
    int clusterSize = wlsClusterConfig.getClusterSize();
    if (wlsClusterConfig.hasDynamicServers()) {
      clusterSize += wlsClusterConfig.getMaxDynamicClusterSize();
    }
    LOGGER.finer("managedServerCount: " + managedServerCount + ", clusterSize: " + clusterSize);
    if (managedServerCount > clusterSize) {
      throw createWebApplicationException(
          Status.BAD_REQUEST,
          MessageKeys.SCALE_COUNT_GREATER_THAN_CONFIGURED,
          managedServerCount,
          clusterSize,
          cluster,
          cluster);
    }
  }

  private static String getAdminServiceSecretName(Domain domain) {
    return domain.getSpec().getAdminSecret() == null
        ? null
        : domain.getSpec().getAdminSecret().getName();
  }

  protected ClusterConfig getClusterConfig(Domain dom, String namespace, String cluster) {
    WlsDomainConfig scan =
        getWlsDomainConfig(
            namespace, getAdminServerServiceName(dom.getSpec()), getAdminServiceSecretName(dom));
    DomainConfig domainConfig =
        LifeCycleHelper.instance()
            .getEffectiveDomainConfig(
                dom, scan.getStandaloneServerConfigs().keySet(), scan.getClusters());
    return domainConfig.getClusters().get(cluster);
  }

  protected WlsClusterConfig getWlsClusterConfig(
      String namespace, String cluster, String adminServerServiceName, String adminSecretName) {
    WlsDomainConfig wlsDomainConfig =
        getWlsDomainConfig(namespace, adminServerServiceName, adminSecretName);
    return wlsDomainConfig.getWlsClusterConfig(cluster);
  }

  protected WlsDomainConfig getWlsDomainConfig(
      String namespace, String adminServerServiceName, String adminSecretName) {
    WlsRetriever wlsConfigRetriever =
        WlsRetriever.create(namespace, adminServerServiceName, adminSecretName);
    return wlsConfigRetriever.readConfig();
  }

  private Map<String, WlsClusterConfig> getWLSConfiguredClusters(
      String namespace, String adminServerServiceName, String adminSecretName) {
    WlsDomainConfig wlsDomainConfig =
        getWlsDomainConfig(namespace, adminServerServiceName, adminSecretName);
    return wlsDomainConfig.getWlsClusterConfigs();
  }

  private Domain findDomain(String domainUID) {
    List<Domain> domains = getDomainsList();
    return findDomain(domainUID, domains);
  }

  protected Domain findDomain(String domainUID, List<Domain> domains) {
    for (Domain domain : domains) {
      if (domainUID.equals(domain.getSpec().getDomainUID())) {
        return domain;
      }
    }

    throw createWebApplicationException(
        Status.NOT_FOUND, MessageKeys.MATCHING_DOMAIN_NOT_FOUND, domainUID);
  }

  private WebApplicationException handleApiException(ApiException e) {
    // TBD - what about e.getResponseHeaders?
    return createWebApplicationException(e.getCode(), e.getResponseBody());
  }

  private WebApplicationException createWebApplicationException(
      Status status, String msgId, Object... params) {
    String msg = formatMessage(msgId, params);
    return createWebApplicationException(status, msg);
  }

  private WebApplicationException createWebApplicationException(Status status, String msg) {
    return createWebApplicationException(status.getStatusCode(), msg);
  }

  private WebApplicationException createWebApplicationException(int status, String msg) {
    ResponseBuilder rb = Response.status(status);
    if (msg != null) {
      rb.entity(msg);
    }
    return new WebApplicationException(rb.build());
  }

  private String formatMessage(String msgId, Object... params) {
    if (params == null || params.length == 0) {
      return getResourceBundle().getString(msgId);
    }

    String msg = getResourceBundle().getString(msgId);
    MessageFormat formatter = new MessageFormat(msg);
    return formatter.format(params);
  }

  private ResourceBundle getResourceBundle() {
    for (Logger l = LOGGER.getUnderlyingLogger(); l != null; l = l.getParent()) {
      ResourceBundle rb = l.getResourceBundle();
      if (rb != null) {
        return rb;
      }
    }
    throw new AssertionError(formatMessage(MessageKeys.RESOURCE_BUNDLE_NOT_FOUND));
  }
}
