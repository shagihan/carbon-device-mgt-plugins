/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.device.mgt.extensions.remote.session;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONObject;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.InvalidDeviceException;
import org.wso2.carbon.device.mgt.common.authorization.DeviceAccessAuthorizationException;
import org.wso2.carbon.device.mgt.common.operation.mgt.Activity;
import org.wso2.carbon.device.mgt.common.operation.mgt.Operation;
import org.wso2.carbon.device.mgt.common.operation.mgt.OperationManagementException;
import org.wso2.carbon.device.mgt.core.DeviceManagementConstants;
import org.wso2.carbon.device.mgt.core.operation.mgt.ConfigOperation;
import org.wso2.carbon.device.mgt.extensions.remote.session.authentication.AuthenticationInfo;
import org.wso2.carbon.device.mgt.extensions.remote.session.authentication.OAuthAuthenticator;
import org.wso2.carbon.device.mgt.extensions.remote.session.constants.RemoteSessionConstants;
import org.wso2.carbon.device.mgt.extensions.remote.session.dto.RemoteSession;
import org.wso2.carbon.device.mgt.extensions.remote.session.exception.RemoteSessionManagementException;
import org.wso2.carbon.device.mgt.extensions.remote.session.internal.RemoteSessionManagementDataHolder;

import javax.websocket.CloseReason;
import javax.websocket.Session;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Class @{@link RemoteSessionManagementServiceImpl} is the implementation of @{@link RemoteSessionManagementService}
 * which is used manage initial connection, sending messages to peer session, manage and close the session.
 */
public class RemoteSessionManagementServiceImpl implements RemoteSessionManagementService {

    private static final Log log = LogFactory.getLog(RemoteSessionManagementServiceImpl.class);

    @Override
    public void initializeSession(Session session, String deviceType, String deviceId, String operationId) throws
            RemoteSessionManagementException {

        // Check whether required configurations are enabled
        if (!RemoteSessionManagementDataHolder.getInstance().isEnabled()) {
            throw new RemoteSessionManagementException("Remote session feature is disabled.");
        } else if (RemoteSessionManagementDataHolder.getInstance().getServerUrl() == null) {
            throw new RemoteSessionManagementException("Server url has not been configured.");
        }

        // Read Query Parameters for obtain the token
        String token = getTokenFromSession(session);

        // if session initiated using operation id means request came from device.
        if (operationId == null) {
            // Validate the token
            OAuthAuthenticator oAuthAuthenticator = RemoteSessionManagementDataHolder.getInstance().getOauthAuthenticator();
            AuthenticationInfo authenticationInfo = oAuthAuthenticator.isAuthenticated(token);

            if (authenticationInfo != null && authenticationInfo.isAuthenticated()) {
                try {
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(authenticationInfo
                                    .getTenantDomain()
                            , true);
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(authenticationInfo.getUsername());
                    if (deviceId != null && !deviceId.isEmpty() && deviceType != null && !deviceType.isEmpty()) {
                        DeviceIdentifier deviceIdentifier = new DeviceIdentifier();
                        deviceIdentifier.setId(deviceId);
                        deviceIdentifier.setType(deviceType);

                        // Check authorization of user for given device
                        boolean userAuthorized = RemoteSessionManagementDataHolder.getInstance()
                                .getDeviceAccessAuthorizationService()
                                .isUserAuthorized(deviceIdentifier, authenticationInfo.getUsername());
                        if (userAuthorized) {
                            // set common settings for session
                            session.setMaxBinaryMessageBufferSize(RemoteSessionManagementDataHolder.getInstance()
                                    .getMaxMessageBufferSize());
                            session.setMaxTextMessageBufferSize(RemoteSessionManagementDataHolder.getInstance()
                                    .getMaxMessageBufferSize());
                            session.setMaxIdleTimeout(RemoteSessionManagementDataHolder.getInstance().getMaxIdleTimeout());

                            initializeClientSession(session, authenticationInfo.getTenantDomain(), deviceType, deviceId);

                            log.info("Current remote sessions count: " + RemoteSessionManagementDataHolder.getInstance()
                                    .getSessionMap().size());

                        } else {
                            throw new RemoteSessionManagementException("Missing device Id or type ");
                        }
                    } else {
                        throw new RemoteSessionManagementException("Unauthorized Access for the device Type : " + deviceType
                                + " , deviceId : " + deviceId);
                    }
                } catch (OperationManagementException | InvalidDeviceException e) {
                    throw new RemoteSessionManagementException("Error occurred while adding initial operation for the " +
                            "device Type : " + deviceType + " , deviceId : " + deviceId);
                } catch (DeviceAccessAuthorizationException e) {
                    throw new RemoteSessionManagementException("Error occurred while device access authorization for the " +
                            "device Type : " + deviceType + " , " + "deviceId : " + deviceId);
                } finally {
                    PrivilegedCarbonContext.endTenantFlow();
                }

            } else {
                throw new RemoteSessionManagementException("Invalid token");
            }

        } else {
            // set common settings for session
            session.setMaxBinaryMessageBufferSize(RemoteSessionManagementDataHolder.getInstance()
                    .getMaxMessageBufferSize());
            session.setMaxTextMessageBufferSize(RemoteSessionManagementDataHolder.getInstance()
                    .getMaxMessageBufferSize());
            session.setMaxIdleTimeout(RemoteSessionManagementDataHolder.getInstance().getMaxIdleTimeout());

            if (token == null || token.isEmpty()) {
                String message = "Could not find a UUID related to the remote session.";
                log.error(message);
                throw new RemoteSessionManagementException(message);
            } else {
                String tenantDomain = RemoteSessionManagementDataHolder.getInstance().getUuidToTenantMap().remove(token);
                if (tenantDomain == null || tenantDomain.isEmpty()) {
                    String message = "Invalid UUID (" + token + "), could not create the remote session.";
                    log.error(message);
                    throw new RemoteSessionManagementException(message);
                } else {
                    // create new device session
                    initializeDeviceSession(session, tenantDomain, deviceType, deviceId, operationId, token);
                }
            }
        }
    }

    @Override
    public void initializeSession(Session session, String deviceType, String deviceId) throws
            RemoteSessionManagementException {
        initializeSession(session, deviceType, deviceId, null);
    }

    /**
     * Implements the behaviour of sending message to peer connection
     *
     * @param session Web socket RemoteSession
     * @param message String message needs to send to peer connection
     * @throws RemoteSessionManagementException throws when session cannot be made due to invalid data
     * @throws RemoteSessionManagementException throws when session has error with accessing device resources
     */
    @Override
    public void sendMessageToPeer(Session session, String message) throws RemoteSessionManagementException {
        JSONObject jsonObject = new JSONObject(message);
        RemoteSession remoteSession = RemoteSessionManagementDataHolder.getInstance().getSessionMap().get(session
                .getId());
        if (remoteSession != null) {
            remoteSession.sendMessageToPeer(jsonObject.toString());
        } else {
            throw new RemoteSessionManagementException("Remote Session cannot be found ");
        }
    }


    /**
     * Implements the behaviour of sending message to peer connection
     *
     * @param session Web socket RemoteSession
     * @param message Byte message needs to send to peer connection
     * @throws RemoteSessionManagementException throws when session cannot be made due to invalid data
     * @throws RemoteSessionManagementException throws when session has error with accessing device resources
     */
    @Override
    public void sendMessageToPeer(Session session, byte[] message) throws RemoteSessionManagementException {

        RemoteSession remoteSession = RemoteSessionManagementDataHolder.getInstance().getSessionMap().get(session
                .getId());
        if (remoteSession != null) {
            remoteSession.sendMessageToPeer(message);
        } else {
            throw new RemoteSessionManagementException("Remote Session cannot be found ");
        }
    }

    /**
     * Closing the session and cleanup the resources
     *
     * @param session Web socket Remote Session
     */
    @Override
    public void endSession(Session session, String closeReason) {

        RemoteSession remoteSession = RemoteSessionManagementDataHolder.getInstance().getSessionMap().remove(session
                .getId());
        if (remoteSession != null) {
            //String operationId = remoteSession.getOperationId();
            RemoteSessionManagementDataHolder.getInstance().getUuidToTenantMap().remove(remoteSession.getUuidToValidateDevice());
            String deviceKey = remoteSession.getTenantDomain() + "/" + remoteSession.getDeviceType() + "/" +
                    remoteSession.getDeviceId();
            RemoteSession lastSession = RemoteSessionManagementDataHolder.getInstance()
                    .getActiveDeviceClientSessionMap().get(deviceKey);
            if (lastSession != null && lastSession.getMySession().getId().equals(session.getId())) {
                RemoteSessionManagementDataHolder.getInstance().getActiveDeviceClientSessionMap().remove
                        (deviceKey);
            }
            if (remoteSession.getPeerSession() != null) {
                Session peerSession = remoteSession.getPeerSession().getMySession();
                if (peerSession != null) {
                    RemoteSessionManagementDataHolder.getInstance().getSessionMap().remove(peerSession.getId());
                    if (lastSession != null && lastSession.getMySession().getId().equals(peerSession.getId())) {
                        RemoteSessionManagementDataHolder.getInstance().getActiveDeviceClientSessionMap().remove
                                (deviceKey);
                    }
                    if (peerSession.isOpen()) {
                        try {
                            peerSession.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, closeReason));
                        } catch (IOException ex) {
                            if (log.isDebugEnabled()) {
                                log.error("Failed to disconnect the client.", ex);
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Starting new client session
     *
     * @param session      Web socket Session
     * @param tenantDomain Tenant domain
     * @param deviceType   Device Type
     * @param deviceId     Device Id
     * @throws RemoteSessionManagementException throws when session has errors with accessing device resources
     * @throws OperationManagementException     throws when error occured during new operation
     * @throws InvalidDeviceException           throws when incorrect device identifier
     */
    private void initializeClientSession(Session session, String tenantDomain, String deviceType, String deviceId) throws RemoteSessionManagementException,
            OperationManagementException, InvalidDeviceException {

        String uuidToValidateDevice = UUID.randomUUID().toString();
        RemoteSession clientRemote = new RemoteSession(session, tenantDomain, deviceType, deviceId, RemoteSessionConstants
                .CONNECTION_TYPE.CLIENT, uuidToValidateDevice);
        String deviceKey = tenantDomain + "/" + deviceType + "/" + deviceId;
        // Create new remote control operation to start the session
        RemoteSession activeSession = RemoteSessionManagementDataHolder.getInstance().getActiveDeviceClientSessionMap
                ().putIfAbsent(deviceKey, clientRemote);
        if (activeSession != null && activeSession.getMySession().isOpen() && activeSession
                .getPeerSession() == null) {
            throw new RemoteSessionManagementException("Another client session waiting on device to connect.");
        } else {
            // if there is pending session exists but already closed, then we need to remove it.
            if (activeSession != null) {
                RemoteSessionManagementDataHolder.getInstance().getActiveDeviceClientSessionMap().remove
                        (deviceKey);
                try {
                    activeSession.getMySession().close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Remote " +
                            "session closed due to new session request"));
                } catch (IOException ex) {
                    if (log.isDebugEnabled()) {
                        log.error("Failed to disconnect the client.", ex);
                    }
                }
                // Use put if absent for adding session to waiting list since we need to overcome
                // multithreaded session requests.
                activeSession = RemoteSessionManagementDataHolder.getInstance().getActiveDeviceClientSessionMap()
                        .putIfAbsent(deviceKey, clientRemote);
            }
            // If another client tried to start session same time then active session will be
            // exist. So we are adding session request only no parallel sessions added to map
            if (activeSession == null) {

                // Create operation if session initiated by client
                Operation operation = new ConfigOperation();
                operation.setCode(RemoteSessionConstants.REMOTE_CONNECT);
                operation.setEnabled(true);
                operation.setControl(Operation.Control.NO_REPEAT);
                JSONObject payload = new JSONObject();
                payload.put("serverUrl", RemoteSessionManagementDataHolder.getInstance().getServerUrl());
                payload.put("uuidToValidateDevice", uuidToValidateDevice);
                RemoteSessionManagementDataHolder.getInstance().getUuidToTenantMap
                        ().put(uuidToValidateDevice, tenantDomain);
                if (log.isDebugEnabled()) {
                    log.debug("UUID " + uuidToValidateDevice + " is generated against the tenant : " +
                            RemoteSessionManagementDataHolder.getInstance().getUuidToTenantMap().get(uuidToValidateDevice));
                }
                operation.setPayLoad(payload.toString());
                String date = new SimpleDateFormat(RemoteSessionConstants.DATE_FORMAT_NOW).format(new Date());
                operation.setCreatedTimeStamp(date);
                List<DeviceIdentifier> deviceIdentifiers = new ArrayList<>();
                deviceIdentifiers.add(new DeviceIdentifier(deviceId, deviceType));
                Activity activity = RemoteSessionManagementDataHolder.getInstance().
                        getDeviceManagementProviderService().addOperation(deviceType, operation, deviceIdentifiers);
                clientRemote.setOperationId(activity.getActivityId().replace(DeviceManagementConstants
                        .OperationAttributes.ACTIVITY, ""));
                RemoteSessionManagementDataHolder.getInstance().getSessionMap().put(session.getId(), clientRemote);

                log.info("Client remote session opened for session id: " + session.getId() + " device Type : " +
                        deviceType + " , " + "deviceId : " + deviceId);
            } else {
                throw new RemoteSessionManagementException("Another client session waiting on " +
                        "device to connect.");
            }
        }
    }

    /**
     * Starting new device session
     *
     * @param session      Web socket Session
     * @param tenantDomain Tenant domain
     * @param deviceType   Device Type
     * @param deviceId     Device Id
     * @param operationId  Operation id
     * @throws RemoteSessionManagementException throws when session has errors with accessing device resources
     */
    private void initializeDeviceSession(Session session, String tenantDomain, String deviceType, String deviceId,
                                         String operationId, String uuidToValidateDevice) throws RemoteSessionManagementException {
        String deviceKey = tenantDomain + "/" + deviceType + "/" + deviceId;
        RemoteSession activeSession = RemoteSessionManagementDataHolder.getInstance()
                .getActiveDeviceClientSessionMap().get(deviceKey);
        if (activeSession != null) {
            RemoteSession clientRemote = RemoteSessionManagementDataHolder.getInstance().getSessionMap().get
                    (activeSession.getMySession().getId());
            if (clientRemote != null) {
                if (clientRemote.getOperationId().equals(operationId)) {
                    RemoteSession deviceRemote = new RemoteSession(session, tenantDomain, deviceType, deviceId,
                            RemoteSessionConstants.CONNECTION_TYPE.DEVICE, uuidToValidateDevice);
                    deviceRemote.setOperationId(operationId);
                    deviceRemote.setPeerSession(clientRemote);
                    clientRemote.setPeerSession(deviceRemote);
                    RemoteSessionManagementDataHolder.getInstance().getSessionMap().put(session.getId(), deviceRemote);
                    // Send Remote connect response
                    JSONObject message = new JSONObject();
                    message.put(RemoteSessionConstants.REMOTE_CONNECT_CODE, RemoteSessionConstants.REMOTE_CONNECT);
                    deviceRemote.sendMessageToPeer(message.toString());
                    log.info("Device session opened for session id: " + session.getId() + " device Type : " +
                            deviceType + " , " + "deviceId : " + deviceId);
                } else {
                    throw new RemoteSessionManagementException("Device and Operation information " +
                            "does not matched with client information for operation id: " + operationId + " device " +
                            "Type : " + deviceType + " , " + "deviceId : " + deviceId);
                }
            } else {
                throw new RemoteSessionManagementException("Device session is inactive for " + "operation id: " +
                        operationId + " device Type : " + deviceType + " , " + "deviceId : " + deviceId);
            }
        } else {
            throw new RemoteSessionManagementException("Device session is inactive for operation " + "id: " +
                    operationId + " device Type : " + deviceType + " , " + "deviceId : " + deviceId);
        }

    }

    /**
     * Retrieving the token from the http session
     *
     * @param session WebSocket session
     * @return retrieved token
     */
    private String getTokenFromSession(Session session) {
        if (session == null) {
            return null;
        }
        String queryString = session.getQueryString();
        if (queryString != null) {
            String[] allQueryParamPairs = queryString.split(RemoteSessionConstants.OAuthTokenValidator
                    .QUERY_STRING_SEPERATOR);
            for (String keyValuePair : allQueryParamPairs) {
                String[] queryParamPair = keyValuePair.split(RemoteSessionConstants.OAuthTokenValidator
                        .QUERY_KEY_VALUE_SEPERATOR);
                if (queryParamPair.length != 2) {
                    log.warn("Invalid query string [" + queryString + "] passed in.");
                    break;
                }
                if (queryParamPair[0].equals(RemoteSessionConstants.OAuthTokenValidator.TOKEN_IDENTIFIER)) {
                    return queryParamPair[1];
                }
            }
        }
        return null;
    }
}
