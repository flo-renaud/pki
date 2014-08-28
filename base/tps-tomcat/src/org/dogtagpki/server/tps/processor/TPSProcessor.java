// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2013 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package org.dogtagpki.server.tps.processor;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import netscape.security.x509.RevocationReason;

import org.dogtagpki.server.tps.TPSSession;
import org.dogtagpki.server.tps.TPSSubsystem;
import org.dogtagpki.server.tps.authentication.AuthUIParameter;
import org.dogtagpki.server.tps.authentication.TPSAuthenticator;
import org.dogtagpki.server.tps.channel.SecureChannel;
import org.dogtagpki.server.tps.cms.CARemoteRequestHandler;
import org.dogtagpki.server.tps.cms.CARevokeCertResponse;
import org.dogtagpki.server.tps.cms.TKSComputeRandomDataResponse;
import org.dogtagpki.server.tps.cms.TKSComputeSessionKeyResponse;
import org.dogtagpki.server.tps.cms.TKSEncryptDataResponse;
import org.dogtagpki.server.tps.cms.TKSRemoteRequestHandler;
import org.dogtagpki.server.tps.dbs.ActivityDatabase;
import org.dogtagpki.server.tps.dbs.TPSCertRecord;
import org.dogtagpki.server.tps.dbs.TokenRecord;
import org.dogtagpki.server.tps.engine.TPSEngine;
import org.dogtagpki.server.tps.profile.BaseTokenProfileResolver;
import org.dogtagpki.server.tps.profile.TokenProfileParams;
import org.dogtagpki.tps.apdu.APDU;
import org.dogtagpki.tps.apdu.APDUResponse;
import org.dogtagpki.tps.apdu.ExternalAuthenticateAPDU.SecurityLevel;
import org.dogtagpki.tps.apdu.GetDataAPDU;
import org.dogtagpki.tps.apdu.GetStatusAPDU;
import org.dogtagpki.tps.apdu.GetVersionAPDU;
import org.dogtagpki.tps.apdu.InitializeUpdateAPDU;
import org.dogtagpki.tps.apdu.ListObjectsAPDU;
import org.dogtagpki.tps.apdu.SelectAPDU;
import org.dogtagpki.tps.main.TPSBuffer;
import org.dogtagpki.tps.main.TPSException;
import org.dogtagpki.tps.msg.BeginOpMsg;
import org.dogtagpki.tps.msg.EndOpMsg.TPSStatus;
import org.dogtagpki.tps.msg.ExtendedLoginRequestMsg;
import org.dogtagpki.tps.msg.ExtendedLoginResponseMsg;
import org.dogtagpki.tps.msg.LoginRequestMsg;
import org.dogtagpki.tps.msg.LoginResponseMsg;
import org.dogtagpki.tps.msg.NewPinRequestMsg;
import org.dogtagpki.tps.msg.NewPinResponseMsg;
import org.dogtagpki.tps.msg.StatusUpdateRequestMsg;
import org.dogtagpki.tps.msg.TPSMessage;
import org.dogtagpki.tps.msg.TokenPDURequestMsg;
import org.dogtagpki.tps.msg.TokenPDUResponseMsg;
import org.mozilla.jss.CryptoManager.NotInitializedException;
import org.mozilla.jss.pkcs11.PK11SymKey;

import com.netscape.certsrv.apps.CMS;
import com.netscape.certsrv.authentication.IAuthCredentials;
import com.netscape.certsrv.authentication.IAuthManager;
import com.netscape.certsrv.authentication.IAuthToken;
import com.netscape.certsrv.base.EBaseException;
import com.netscape.certsrv.base.EPropertyNotFound;
import com.netscape.certsrv.base.IConfigStore;
import com.netscape.certsrv.tps.token.TokenStatus;
import com.netscape.symkey.SessionKey;

public class TPSProcessor {

    public static final int RESULT_NO_ERROR = 0;
    public static final int RESULT_ERROR = -1;

    public static final int CPLC_DATA_SIZE = 47;
    public static final int CPLC_MSN_INDEX = 41;
    public static final int CPLC_MSN_SIZE = 4;

    public static final int INIT_UPDATE_DATA_SIZE = 28;
    public static final int DIVERSIFICATION_DATA_SIZE = 10;
    public static final int CARD_CRYPTOGRAM_OFFSET = 20;
    public static final int CARD_CRYPTOGRAM_SIZE = 8;
    public static final int CARD_CHALLENGE_OFFSET = 12;
    public static final int CARD_CHALLENGE_SIZE = 8;

    protected boolean isExternalReg;

    protected TPSSession session;
    //protected TokenRecord tokenRecord;
    protected String selectedTokenType;

    protected String userid = null;
    protected String currentTokenOperation;

    protected BeginOpMsg beginMsg;

    public TPSProcessor(TPSSession session) {
        setSession(session);
    }

    protected void setCurrentTokenOperation(String op) {
        currentTokenOperation = op;
    }

    protected void setSession(TPSSession session) {
        if (session == null) {
            throw new NullPointerException("TPS session is null");
        }
        this.session = session;
    }

    protected TPSSession getSession() {
        return session;
    }

    protected TokenRecord getTokenRecord() {
        TPSSession session = getSession();
        return session.getTokenRecord();
    }

    protected void setBeginMessage(BeginOpMsg msg) {
        beginMsg = msg;
    }

    public BeginOpMsg getBeginMessage() {
        return beginMsg;
    }

    protected void setSelectedTokenType(String theTokenType) {

        if (theTokenType == null) {
            throw new NullPointerException("TPSProcessor.setSelectedTokenType: Attempt to set invalid null token type!");
        }
        CMS.debug("TPS_Processor.setSelectedTokenType: tokenType=" +
                theTokenType);
        selectedTokenType = theTokenType;

        TokenRecord tokenRecord = getTokenRecord();
        tokenRecord.setType(selectedTokenType);
    }

    public String getSelectedTokenType() {
        return selectedTokenType;
    }

    protected TPSBuffer extractTokenMSN(TPSBuffer cplc_data) throws TPSException {
        //Just make sure no one is inputing bogus cplc_data
        if (cplc_data == null || cplc_data.size() < CPLC_DATA_SIZE) {
            throw new TPSException("TPSProcessor.extractTokenMSN: Can't extract token msn from cplc data!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer token_msn = cplc_data.substr(CPLC_MSN_INDEX, CPLC_MSN_SIZE);
        return token_msn;

    }

    protected TPSBuffer extractTokenCUID(TPSBuffer cplc_data) throws TPSException {
        //Just make sure no one is inputing bogus cplc_data
        if (cplc_data == null || cplc_data.size() < CPLC_DATA_SIZE) {
            CMS.debug("TPS_Processor.extractTokenCUID: cplc_data: invalid length.");
            throw new TPSException("TPSProcessor.extractTokenCUID: Can't extract cuid from cplc data!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer token1 = cplc_data.substr(3, 4);
        TPSBuffer token2 = cplc_data.substr(19, 2);
        TPSBuffer token3 = cplc_data.substr(15, 4);

        TPSBuffer token_cuid = new TPSBuffer();

        token_cuid.add(token1);
        token_cuid.add(token2);
        token_cuid.add(token3);

        return token_cuid;

    }

    /**
     * Select applet.
     *
     * Global Platform Open Platform Card Specification
     * Version 2.0.1 Page 9-22
     *
     * Sample Data:
     *
     * _____________ CLA
     * | __________ INS
     * | | _______ P1
     * | | | ____ P2
     * | | | | _ Len
     * | | | | |
     * 00 A4 04 00 07
     * 53 4C 42 47 49 4E 41
     *
     * @throws IOException
     * @throws TPSException
     *
     */

    protected APDUResponse selectApplet(byte p1, byte p2, TPSBuffer aid) throws IOException, TPSException {

        CMS.debug("In TPS_Processor.SelectApplet.");

        if (aid == null || aid.size() == 0) {
            throw new TPSException("TPSProcessor.selectApplet: Invalid aid value!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        SelectAPDU select_apdu = new SelectAPDU(p1, p2, aid);

        //return the Response because the caller can
        //decide what to do, not every failure is fatal.
        //For instance the coolkey applet may not yet exist.
        return handleAPDURequest(select_apdu);

    }

    protected TPSBuffer getStatus() throws IOException, TPSException {

        CMS.debug("In TPS_Processor.GetStatus.");

        GetStatusAPDU get_status_apdu = new GetStatusAPDU();

        return handleAPDURequest(get_status_apdu).getData();
    }

    public APDUResponse handleAPDURequest(APDU apdu) throws IOException, TPSException {

        if (apdu == null) {
            throw new TPSException("TPSProcessor.handleAPDURequest: invalid incoming apdu!");
        }

        TokenPDURequestMsg request_msg = new TokenPDURequestMsg(apdu);

        try {
            session.write(request_msg);
        } catch (IOException e) {
            CMS.debug("TPS_Processor.HandleAPDURequest failed WriteMsg: " + e.toString());
            throw e;

        }

        TokenPDUResponseMsg response_msg = null;

        try {
            response_msg = (TokenPDUResponseMsg) session.read();
        } catch (IOException e) {
            CMS.debug("TPS_Processor.HandleAPDURequest failed ReadMsg: " + e.toString());
            throw e;

        }

        return response_msg.getResponseAPDU();
    }

    protected TPSBuffer getCplcData() throws IOException, TPSException {
        CMS.debug("In TPS_Processor.GetData");

        GetDataAPDU get_data_apdu = new GetDataAPDU();

        APDUResponse respApdu = handleAPDURequest(get_data_apdu);

        if (!respApdu.checkResult()) {
            throw new TPSException("TPSProcessor.getCplcData: Can't get data!", TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }
        TPSBuffer cplcData = respApdu.getData();

        if (cplcData.size() != CPLC_DATA_SIZE) {
            throw new TPSException("TPSProcessor.cplcData: Data invalid size!", TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return respApdu.getData();
    }

    protected TPSBuffer getAppletVersion() throws IOException, TPSException {
        //We return null if no applet present
        // This is not an error, the token can be blank.

        CMS.debug("In TPSProcessor.getAppletVersion");

        GetVersionAPDU get_version_apdu = new GetVersionAPDU();

        APDUResponse respApdu = handleAPDURequest(get_version_apdu);

        if (!respApdu.checkResult()) {
            CMS.debug("TPSProcessor.getAppletVersion: No applet version found on card!");
            return null;
        }

        TPSBuffer apdu_data = respApdu.getData();

        if (apdu_data.size() != 6) {
            CMS.debug("TPSProcessor.getAppletVersion: incorrect return data size!");
            throw new TPSException("TPSProcessor.getAppletVersion: invalid applet version string returned!");
        }

        TPSBuffer build_id = apdu_data.substr(0, 4);

        CMS.debug("TPSProcessor.getAppletVersion: returning: " + build_id.toHexString());

        return build_id;

    }

    protected TPSBuffer encryptData(AppletInfo appletInfo, TPSBuffer keyInfo, TPSBuffer plaintextChallenge,
            String connId) throws TPSException {

        TKSRemoteRequestHandler tks = null;

        TKSEncryptDataResponse data = null;

        try {
            tks = new TKSRemoteRequestHandler(connId);
            data = tks.encryptData(appletInfo.getCUID(), keyInfo, plaintextChallenge);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.encryptData: Erorr getting wrapped data from TKS!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        int status = data.getStatus();

        if (status != 0) {
            throw new TPSException("TPSProcessor.computeRandomData: Erorr getting wrapped data from TKS!",
                    TPSStatus.STATUS_ERROR_MAC_ENROLL_PDU);
        }

        return data.getEncryptedData();
    }

    TPSBuffer computeRandomData(int dataSize, String connId) throws TPSException {

        TKSRemoteRequestHandler tks = null;

        TKSComputeRandomDataResponse data = null;

        try {
            tks = new TKSRemoteRequestHandler(connId);
            data = tks.computeRandomData(dataSize);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.computeRandomData: Erorr getting random data from TKS!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        int status = data.getStatus();

        if (status != 0) {
            throw new TPSException("TPSProcessor.computeRandomData: Erorr getting random data from TKS!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return data.getRandomData();
    }

    protected TPSBuffer initializeUpdate(byte keyVersion, byte keyIndex, TPSBuffer randomData) throws IOException,
            TPSException {

        CMS.debug("In TPS_Processor.initializeUpdate.");
        InitializeUpdateAPDU initUpdate = new InitializeUpdateAPDU(keyVersion, keyIndex, randomData);

        APDUResponse resp = handleAPDURequest(initUpdate);

        if (!resp.checkResult()) {
            CMS.debug("TPSProcessor.initializeUpdate: Failed intializeUpdate!");
            throw new TPSException("TPSBuffer.initializeUpdate: Failed initializeUpdate!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        TPSBuffer data = resp.getResultDataNoCode();

        if (data.size() != INIT_UPDATE_DATA_SIZE) {
            throw new TPSException("TPSBuffer.initializeUpdate: Invalid response from token!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return data;

    }

    protected SecureChannel setupSecureChannel() throws TPSException, IOException {
        SecureChannel channel = null;

        //Create a standard secure channel with current key set.
        CMS.debug("TPSProcessor.setupSecureChannel: No arguments entering...");

        int defKeyVersion = getChannelDefKeyVersion();
        int defKeyIndex = getChannelDefKeyIndex();

        channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, SecurityLevel.SECURE_MSG_MAC_ENC,
                getTKSConnectorID());

        channel.externalAuthenticate();

        return channel;
    }

    protected SecureChannel setupSecureChannel(byte keyVersion, byte keyIndex, SecurityLevel securityLevel,
            String connId)
            throws IOException, TPSException {

        //Assume generating host challenge on TKS, we no longer support not involving the TKS.

        TPSBuffer randomData = computeRandomData(8, connId);
        CMS.debug("TPSProcessor.setupSecureChannel: obtained randomData: " + randomData.toHexString());

        TPSBuffer initUpdateResp = initializeUpdate(keyVersion, keyIndex, randomData);

        TPSBuffer key_diversification_data = initUpdateResp.substr(0, DIVERSIFICATION_DATA_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: diversification data: " + key_diversification_data.toHexString());

        TPSBuffer key_info_data = initUpdateResp.substr(DIVERSIFICATION_DATA_SIZE, 2);
        CMS.debug("TPSProcessor.setupSecureChannel: key info data: " + key_info_data.toHexString());

        TokenRecord tokenRecord = getTokenRecord();
        tokenRecord.setKeyInfo(key_info_data.toHexStringPlain());

        TPSBuffer card_cryptogram = initUpdateResp.substr(CARD_CRYPTOGRAM_OFFSET, CARD_CRYPTOGRAM_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: card cryptogram: " + card_cryptogram.toHexString());

        TPSBuffer card_challenge = initUpdateResp.substr(CARD_CHALLENGE_OFFSET, CARD_CHALLENGE_SIZE);
        CMS.debug("TPSProcessor.setupSecureChannel: card challenge: " + card_challenge.toHexString());

        SecureChannel channel = null;

        try {
            channel = generateSecureChannel(connId, key_diversification_data, key_info_data, card_challenge,
                    card_cryptogram,
                    randomData);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.setupSecureChannel: Can't set up secure channel: " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        return channel;

    }

    protected SecureChannel generateSecureChannel(String connId, TPSBuffer keyDiversificationData,
            TPSBuffer keyInfoData, TPSBuffer cardChallenge, TPSBuffer cardCryptogram, TPSBuffer hostChallenge)
            throws EBaseException, TPSException {

        CMS.debug("TPSProcessor.generateSecureChannel: entering..");

        TPSEngine engine = getTPSEngine();

        SecureChannel channel = null;
        TPSBuffer hostCryptogram = null;

        TKSComputeSessionKeyResponse resp = engine.computeSessionKey(keyDiversificationData, keyInfoData,
                cardChallenge, hostChallenge, cardCryptogram,
                connId, getSelectedTokenType());

        hostCryptogram = resp.getHostCryptogram();

        if (hostCryptogram == null) {
            new TPSException("TPSProcessor.generateSecureChannel: No host cryptogram returned from token!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        PK11SymKey sharedSecret = null;

        try {
            sharedSecret = getSharedSecretTransportKey(connId);
        } catch (Exception e) {
            CMS.debug(e);
            throw new TPSException("TPSProcessor.generateSecureChannel: Can't get shared secret key!: " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        PK11SymKey sessionKey = null;
        PK11SymKey encSessionKey = null;
        String tokenName = "Internal Key Storage Token";

        try {
            TPSBuffer sessionKeyWrapped = resp.getSessionKey();
            TPSBuffer encSessionKeyWrapped = resp.getEncSessionKey();

            sessionKey = SessionKey.UnwrapSessionKeyWithSharedSecret(tokenName, sharedSecret,
                    sessionKeyWrapped.toBytesArray());

            if (sessionKey == null) {
                CMS.debug("TPSProcessor.generateSecureChannel: Can't extract session key!");
                throw new TPSException("TPSProcessor.generateSecureChannel: Can't extract session key!",
                        TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
            }
            CMS.debug("TPSProcessor.generateSecureChannel: retrieved session key: " + sessionKey);

            encSessionKey = SessionKey.UnwrapSessionKeyWithSharedSecret(tokenName, sharedSecret,
                    encSessionKeyWrapped.toBytesArray());

            if (encSessionKey == null) {
                CMS.debug("TPSProcessor.generateSecureChannel: Can't extract enc session key!");
                throw new TPSException("TPSProcessor.generateSecureChannel: Can't extract enc session key!",
                        TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
            }

            CMS.debug("TPSProcessor.generateSecureChannel: retrieved enc session key: " + encSessionKey);
        } catch (Exception e) {
            CMS.debug(e);
            e.printStackTrace();
            throw new TPSException("TPSProcessor.generateSecureChannel: Problem extracting session keys! " + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        TPSBuffer drmDesKey = null;
        TPSBuffer kekDesKey = null;
        TPSBuffer keyCheck = null;

        drmDesKey = resp.getDRM_Trans_DesKey();
        keyCheck = resp.getKeyCheck();
        kekDesKey = resp.getKekWrappedDesKey();

        if (checkServerSideKeyGen(connId)) {

            CMS.debug("TPSProcessor.generateSecureChannel: drmDesKey: " + drmDesKey + " kekDesKey : " + kekDesKey
                    + " keyCheck: " + keyCheck);
            //ToDo handle server side keygen.

        }
        channel = new SecureChannel(this, sessionKey, encSessionKey, drmDesKey,
                kekDesKey, keyCheck, keyDiversificationData, cardChallenge,
                cardCryptogram, hostChallenge, hostCryptogram, keyInfoData);

        return channel;
    }

    protected void upgradeApplet(String operation, String new_version, SecurityLevel securityLevel,
            Map<String, String> extensions, String connId, int startProgress, int endProgress) throws IOException,
            TPSException {

        TPSBuffer netkeyAIDBuff = null;
        TPSBuffer cardMgrAIDBuff = null;
        TPSBuffer netkeyPAIDBuff = null;

        netkeyAIDBuff = getNetkeyAID();
        netkeyPAIDBuff = getNetkeyPAID();
        cardMgrAIDBuff = getCardManagerAID();

        int channelBlockSize = getChannelBlockSize();
        int channelInstanceSize = getChannelInstanceSize();
        int channelAppletMemSize = getAppletMemorySize();
        int defKeyVersion = getChannelDefKeyVersion();
        int defKeyIndex = getChannelDefKeyIndex();

        byte[] appletData = null;

        TokenRecord tokenRecord = getTokenRecord();

        String directory = getAppletDirectory(operation);

        CMS.debug("TPSProcessor.upgradeApplet: applet target directory: " + directory);

        String appletFileExt = getAppletExtension();

        String appletFilePath = directory + "/" + new_version + "." + appletFileExt;

        CMS.debug("TPSProcessor.upgradeApplet: targe applet file name: " + appletFilePath);

        appletData = getAppletFileData(appletFilePath);

        APDUResponse select = selectApplet((byte) 0x04, (byte) 0x00, cardMgrAIDBuff);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.upgradeApplet: Can't selelect the card manager!");
        }

        SecureChannel channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, securityLevel, connId);

        channel.externalAuthenticate();
        channel.deleteFileX(netkeyAIDBuff);
        channel.deleteFileX(netkeyPAIDBuff);

        // Next step will be to load the applet file to token.

        TPSBuffer empty = new TPSBuffer();

        channel.installLoad(netkeyPAIDBuff, empty, appletData.length);

        TPSBuffer appletDataBuff = new TPSBuffer(appletData);

        channel.loadFile(appletDataBuff, channelBlockSize, startProgress, endProgress);

        channel.installApplet(netkeyPAIDBuff, netkeyAIDBuff, (byte) 0, channelInstanceSize, channelAppletMemSize);

        //Now select our new applet

        select = selectApplet((byte) 0x04, (byte) 0x00, netkeyAIDBuff);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.upgradeApplet: Cannot select newly created applet!",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }
        tokenRecord.setAppletID(new_version);

    }

    public void selectCoolKeyApplet() throws TPSException, IOException {

        TPSBuffer netkeyAIDBuff = getNetkeyAID();

        APDUResponse select = selectApplet((byte) 0x04, (byte) 0x00, netkeyAIDBuff);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.upgradeApplet: Cannot select newly created applet!",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }
    }

    protected byte[] getAppletFileData(String appletFilePath) throws IOException, TPSException {

        if (appletFilePath == null) {
            throw new TPSException("TPSProcessor.getAppletFileData: Invalid applet file name.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        byte[] contents = null;
        try {
            Path path = Paths.get(appletFilePath);
            contents = Files.readAllBytes(path);

        } catch (IOException e) {
            CMS.debug("TPSProcessor.getAppletFileData: IOException " + e);
            throw e;
        } catch (Exception e) {
            CMS.debug("PSProcessor.getAppletFileData: Exception: " + e);
            throw new TPSException("TPSProcessor.getAppletFileData: Exception: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.getAppletFileData: data: " + contents);

        return contents;
    }

    /**
     * getAuthentication gets Authentication per configuration
     *
     * @param prefix config prefix for tokenType
     * @param tokenType the tokenType(profile)
     * @return Authentication
     */
    public TPSAuthenticator getAuthentication(String prefix, String tokenType)
            throws EBaseException {
        CMS.debug("TPSProcessor.getAuthentication");
        String auditMsg = null;

        if (prefix.isEmpty() || tokenType.isEmpty()) {
            auditMsg = "TPSProcessor.getAuthentication: missing parameters: prefix or tokenType";
            CMS.debug(auditMsg);
            throw new EBaseException(auditMsg);
        }
        IConfigStore configStore = CMS.getConfigStore();
        String configName = prefix + "." + tokenType + ".auth.id";
        String authId;

        CMS.debug("TPSProcessor.getAuthentication: getting config: " +
                configName);
        authId = configStore.getString(configName);
        if (authId == null) {
            auditMsg = "TPSProcessor.getAuthentication: config param not found:" + configName;
            CMS.debug(auditMsg);
            throw new EBaseException(auditMsg);
        }

        TPSSubsystem subsystem =
                (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
        TPSAuthenticator authInst =
                subsystem.getAuthenticationManager().getAuthInstance(authId);
        String authCredNameConf = "auths.instance." + authId + ".authCredName";
        CMS.debug("TPSProcessor.getAuthentication: getting config: " +
                authCredNameConf);
        String authCredName = configStore.getString(authCredNameConf);
        if (authCredName == null) {
            auditMsg = "TPSProcessor.getAuthentication: config param not found:" + authCredNameConf;
            CMS.debug(auditMsg);
            throw new EBaseException(auditMsg);
        }
        authInst.setAuthCredName(authCredName);
        return authInst;
    }

    /**
     * authenticateUser authenticates a user using specified authentication
     *
     * @param op "enrollment", "format", or "pinReset" //TODO: for tokendb activity log
     * @param userAuth the authenticator
     * @param userCred IAuthCredentials obtained from a successful requestUserId call
     * @return IAuthToken information relating to the performed authentication
     *         -- plugin-specific
     */
    public IAuthToken authenticateUser(
            String op,
            TPSAuthenticator userAuth,
            IAuthCredentials userCred)
            throws EBaseException, TPSException {
        /**
         * TODO: isExternalReg is not handled until decision made
         */
        String auditMsg = null;
        CMS.debug("TPSProcessor.authenticateUser");
        if (op.isEmpty() || userAuth == null || userCred == null) {
            auditMsg = "TPSProcessor.authenticateUser: missing parameter(s): op, userAuth, or userCred";
            CMS.debug(auditMsg);
            throw new EBaseException(auditMsg);
        }
        IAuthManager auth = userAuth.getAuthManager();

        try {
            // Authenticate user
            IAuthToken aToken = auth.authenticate(userCred);
            if (aToken != null) {
                CMS.debug("TPSProcessor.authenticateUser: authentication success");
                return aToken;
            } else {
                CMS.debug("TPSProcessor.authenticateUser: authentication failure with aToken null");
                throw new TPSException("TPS error user authentication failed.",
                        TPSStatus.STATUS_ERROR_LOGIN);
            }
        } catch (EBaseException e) {
            CMS.debug("TPSProcessor.authenticateUser: authentication failure:" + e);
            throw new TPSException("TPS error user authentication failed.",
                    TPSStatus.STATUS_ERROR_LOGIN);
        }
    }

    /**
     * requestUserId sends message to client to request for user credential
     * per authentication plugin
     *
     * @param op "enrollment", "format", or "pinReset" //TODO: for tokendb activity log
     * @param cuid token CUID //TODO: for tokendb activity log
     * @param extensions message extensions
     * @return IAuthCredentials containing user credential needed for authentication
     */
    IAuthCredentials requestUserId(String op, String cuid, TPSAuthenticator auth, Map<String, String> extensions)
            throws IOException, TPSException, EBaseException {
        CMS.debug("TPSProcessor.requestUserId");
        if (op.isEmpty() ||
                cuid.isEmpty() || auth == null) {
            CMS.debug("TPSProcessor.requestUserId: missing parameter(s): op, cuid, or auth");
            throw new EBaseException("TPSProcessor.requestUserId: missing parameter(s): op, cuid, or auth");
        }

        IAuthCredentials login;
        if (extensions != null &&
                extensions.get("extendedLoginRequest") != null) {
            // default locale will be "en"
            String locale = extensions.get("locale");
            if (extensions.get("locale") == null) {
                locale = "en";
            }
            // title
            String title = auth.getUiTitle(locale);
            if (title.isEmpty())
                title = auth.getUiTitle("en");
            // description
            String description = auth.getUiDescription(locale);
            if (description.isEmpty())
                description = auth.getUiTitle("en");
            // parameters
            HashMap<String, AuthUIParameter> authParamSet = auth.getUiParamSet();
            Set<String> params = new HashSet<String>();
            for (Map.Entry<String, AuthUIParameter> entry : authParamSet.entrySet()) {
                params.add(auth.getUiParam(entry.getKey()).toString(locale));
                CMS.debug("TPSProcessor.requestUserId: for extendedLoginRequest, added param: " +
                        auth.getUiParam(entry.getKey()).toString(locale));
            }

            login = requestExtendedLogin(0 /* invalid_pw */, 0 /* blocked */,
                    params, title, description, auth);
        } else {
            login = requestLogin(0 /* invalid_pw */, 0 /* blocked */, auth);
        }

        return login;
    }

    /**
     * mapCredFromMsgResponse fills up authManager required auth credentials
     * with mapped values from client
     * configuration example:
     *
     * auths.instance.ldap1.ui.id.UID.credMap.msgCred.extlogin=UID
     * auths.instance.ldap1.ui.id.UID.credMap.msgCred.login=screen_name
     * auths.instance.ldap1.ui.id.UID.credMap.authCred=uid
     *
     * auths.instance.ldap1.ui.id.PASSWORD.credMap.msgCred.extlogin=PASSWORD
     * auths.instance.ldap1.ui.id.PASSWORD.credMap.msgCred.login=password
     * auths.instance.ldap1.ui.id.PASSWORD.credMap.authCred=pwd
     *
     * @param response the message response to be mapped
     * @param auth the authentication for mapping consultation
     * @return IAuthCredentials auth credential for auth manager
     */
    public IAuthCredentials mapCredFromMsgResponse(TPSMessage response, TPSAuthenticator auth, boolean extendedLogin)
            throws EBaseException {
        CMS.debug("TPSProcessor.mapCredFromMsgResponse");
        if (response == null || auth == null) {
            CMS.debug("TPSProcessor.mapCredFromMsgResponse: missing parameter(s): response or auth");
            throw new EBaseException("TPSProcessor.mapCredFromMsgResponse: missing parameter(s): response or auth");
        }
        IAuthCredentials login =
                new com.netscape.certsrv.authentication.AuthCredentials();

        String[] requiredCreds = auth.getAuthManager().getRequiredCreds();
        for (String cred : requiredCreds) {
            String name = auth.getCredMap(cred, extendedLogin);
            CMS.debug("TPSProcessor.mapCredFromMsgResponse: cred=" + cred + " &name=" +
                    name);
            login.set(cred, response.get(name));
        }

        return login;
    }

    /**
     * Requests login ID and password from user.
     */
    public IAuthCredentials requestExtendedLogin(int invalidPW, int blocked,
            Set<String> parameters,
            String title,
            String description,
            TPSAuthenticator auth)
            throws IOException, TPSException, EBaseException {

        CMS.debug("TPSProcessor.requestExtendedLogin");
        if (parameters == null || title.isEmpty() ||
                description.isEmpty() || auth == null) {
            CMS.debug("TPSProcessor.requestExtendedLogin: missing parameter(s): parameters, title, description, or auth");
            throw new EBaseException(
                    "TPSProcessor.requestExtendedLogin: missing parameter(s): parameters, title, description, or auth");
        }
        ExtendedLoginRequestMsg loginReq =
                new ExtendedLoginRequestMsg(invalidPW, blocked, parameters, title, description);

        try {
            session.write(loginReq);
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestExtendedLogin failed WriteMsg: " + e.toString());
            throw e;
        }
        CMS.debug("TPSProcessor.requestExtendedLogin: extendedLoginRequest sent");

        ExtendedLoginResponseMsg loginResp = null;
        try {
            loginResp = (ExtendedLoginResponseMsg) session.read();
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestExtendedLogin failed ReadMsg: " + e.toString());
            throw e;
        }

        IAuthCredentials login = mapCredFromMsgResponse(loginResp, auth, true /*extendedLogin*/);

        return login;
    }

    /**
     * Requests login ID and password from user.
     */
    public IAuthCredentials requestLogin(int invalidPW, int blocked,
            TPSAuthenticator auth)
            throws IOException, TPSException, EBaseException {

        CMS.debug("TPSProcessor.requestLogin");
        if (auth == null) {
            CMS.debug("TPSProcessor.requestLogin: missing parameter(s): parameters, title, description, or auth");
            throw new EBaseException(
                    "TPSProcessor.requestLogin: missing parameter(s): parameters, title, description, or auth");
        }
        LoginRequestMsg loginReq = new LoginRequestMsg(invalidPW, blocked);

        try {
            session.write(loginReq);
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestLogin failed WriteMsg: " + e.toString());
            throw e;
        }
        CMS.debug("TPSProcessor.requestLogin: loginRequest sent");

        LoginResponseMsg loginResp = null;
        try {
            loginResp = (LoginResponseMsg) session.read();
        } catch (IOException e) {
            CMS.debug("TPSProcessor.requestLogin failed ReadMsg: " + e.toString());
            throw e;
        }

        IAuthCredentials login = mapCredFromMsgResponse(loginResp, auth, false /*not extendedLogin*/);
        return login;
    }

    /*
     * fillTokenRecord -
     *     - retrieves token record from tokendb if it exists, or
     *     - creates a new token record
     *     this in-memory copy of tokenRecord is to be set in the TPSSession
     */
    protected void fillTokenRecord(TokenRecord tokenRecord, AppletInfo appletInfo)
            throws TPSException {
        String method = "TPSProcessor.fillTokenRecord";
        CMS.debug(method + ": begins");
        if (tokenRecord == null || appletInfo == null) {
            CMS.debug(method + ": params tokenRecord and appletInfo cannot be null");
            throw new TPSException(
                    method + ": missing parameter(s): parameter appletInfo");
        }

        byte app_major_version = appletInfo.getAppMajorVersion();
        byte app_minor_version = appletInfo.getAppMinorVersion();
        TPSBuffer build_id = null;
        try {
            build_id = getAppletVersion();
        } catch (IOException e) {
            CMS.debug(method + ": failed getting applet version:" + e + " ... continue");
        }
        if (build_id != null) {
            tokenRecord.setAppletID(Integer.toHexString(app_major_version) + "."
                    + Integer.toHexString(app_minor_version) + "." +
                    build_id.toHexStringPlain());
        }

        CMS.debug(method + ": ends");

    }

    protected String getCAConnectorID() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String id = null;

        String config = "op." + currentTokenOperation + "." + selectedTokenType + ".ca.conn";

        try {
            id = configStore.getString(config, "ca1");
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getCAConnectorID: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getCAConectorID: returning: " + id);

        return id;
    }

    /*
     * revokeCertificates revokes certificates on the token specified
     * @param cuid the cuid of the token to revoke certificates
     * @return auditMsg captures the audit message
     * @throws TPSException in case of error
     */
    protected void revokeCertificates(String cuid) throws TPSException {
        String auditMsg = "";
        final String method = "TPSProcessor.revokeCertificates";

        //bail out if not configured to revoke cert
        IConfigStore configStore = CMS.getConfigStore();
        String configName = TPSEngine.OP_FORMAT_PREFIX + "." + selectedTokenType + ".revokeCert";
        boolean revokeCert = false;
        try {
            revokeCert = configStore.getBoolean(configName, false);
        } catch (EBaseException e) {
            auditMsg = method + ": config not found: "+ configName +
                    "; default to false";
            CMS.debug(auditMsg);
            return;
        }
        if (!revokeCert) {
            auditMsg = method + ":  revokeCert = false";
            CMS.debug(auditMsg);
            return;
        }

        if (cuid == null) {
            auditMsg = "cuid null";
            CMS.debug(method + ":" + auditMsg);
            throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
        }
        CMS.debug(method + ": begins for cuid:" + cuid);
        TPSSubsystem tps = (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
        boolean isTokenPresent = tps.tdb.isTokenPresent(cuid);
        if (!isTokenPresent) {
            auditMsg = method + ": token not found: "+ cuid;
            CMS.debug(auditMsg);
            throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
        }

        String caConnId = getCAConnectorID();
        CARemoteRequestHandler caRH = null;
        try {
            caRH = new CARemoteRequestHandler(caConnId);
        } catch (EBaseException e) {
            auditMsg = method + ": getting CARemoteRequestHandler failure";
            CMS.debug(auditMsg);
            throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
        }
        //find all certs belonging to the token
        ArrayList<TPSCertRecord> certRecords = tps.tdb.tdbGetCertificatesByCUID(cuid);

        CMS.debug(method + ": found " + certRecords.size() + " certs");

        for (TPSCertRecord cert:certRecords) {
            if (cert.getStatus().equals("revoked")) {
                // already revoked cert should not be on token any more
                CMS.debug(method + ": cert " + cert.getSerialNumber()
                        + " already revoked; remove from tokendb and move on");
                try {
                    tps.certDatabase.removeRecord(cert.getId());
                } catch (Exception e) {
                    auditMsg = method + ": removeRecord failed";
                    CMS.debug(auditMsg);
                    throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
                }
                continue;
            }

            String origin = cert.getOrigin();
            if (origin!= null && !origin.equals(cuid)) {
                /*
                 * Raidzilla Bug #57803:
                 * If the certificate is not originally created for this
                 * token, we should not revoke the certificate here.
                 * To figure out if this certificate is originally created
                 * for this token, we check the tokenOrigin attribute.
                 */
                CMS.debug(method + ": cert " + cert.getSerialNumber()
                        + " originally created for this token: "+ origin +
                        " while current token: " + cuid
                        + "; Remove from tokendb and skip the revoke");
                try {
                    tps.certDatabase.removeRecord(cert.getId());
                } catch (Exception e) {
                    auditMsg = method + ": removeRecord failed";
                    CMS.debug(auditMsg);
                    throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
                }
                continue;
            }
            if (origin == null) {
                // no tokenOrigin, then don't care, keep going
                CMS.debug(method + ": tokenOrigin is not present in tokendb cert record");
            }

            // revoke the cert
            /*
             * if the certificates are revoked_on_hold, don't do anything because the certificates may
             * be referenced by more than one token.
             */
            if (cert.getStatus().equals("revoked_on_hold")) {
                CMS.debug(method + ": cert " + cert.getSerialNumber()
                        + " has status revoked_on_hold; remove from tokendb and move on");
                try {
                    tps.certDatabase.removeRecord(cert.getId());
                } catch (Exception e) {
                    auditMsg = method + ": removeRecord failed";
                    CMS.debug(auditMsg);
                    throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
                }
                continue;
            }
            configName = TPSEngine.OP_FORMAT_PREFIX + "." + selectedTokenType + ".revokeCert.revokeReason";
            RevocationReason revokeReason = RevocationReason.UNSPECIFIED;
            try {
                int revokeReasonInt = configStore.getInteger(configName);
                revokeReason = RevocationReason.fromInt(revokeReasonInt);
            } catch (EBaseException e) {
                auditMsg = method + ": config not found: " + configName +
                        "; default to unspecified";
                CMS.debug(auditMsg);
                revokeReason = RevocationReason.UNSPECIFIED;
            }
            String hexSerial = cert.getSerialNumber();
            if (hexSerial.length() >= 3 && hexSerial.startsWith("0x")) {
                String serial = hexSerial.substring(2); // skip over the '0x'
                BigInteger bInt = new BigInteger(serial, 16);
                String serialStr = bInt.toString();
                CMS.debug(method + ": found cert hex serial: " + serial +
                        " dec serial:" + serialStr);
                try {
                    CARevokeCertResponse response =
                            caRH.revokeCertificate(true, serialStr, cert.getCertificate(),
                                    revokeReason);
                    CMS.debug(method + ": response status =" + response.getStatus());
                } catch (EBaseException e) {
                    auditMsg = method + ": revokeCertificate from CA failed:" + e;
                    CMS.debug(auditMsg);

                    if (revokeReason == RevocationReason.CERTIFICATE_HOLD) {
                        tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, session.getTokenRecord(),
                                session.getIpAddress(), auditMsg,
                                "failure");
                    } else {
                        tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, session.getTokenRecord(),
                                session.getIpAddress(), auditMsg,
                                "failure");
                    }
                    throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
                }
            } else {
                auditMsg = "mulformed hex serial number :" + hexSerial;
                CMS.debug(method + ": " + auditMsg);
                tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, session.getTokenRecord(), session.getIpAddress(),
                        auditMsg,
                        "failure");
                throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_REVOKE_CERTIFICATES_FAILED);
            }
            auditMsg = "Certificate " + hexSerial + " revoked";
            tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, session.getTokenRecord(), session.getIpAddress(), auditMsg,
                    "success");

            // delete cert from tokendb
            CMS.debug(method + ": cert " + cert.getSerialNumber()
                    + ": remove from tokendb");
            try {
                tps.certDatabase.removeRecord(cert.getId());
            } catch (Exception e) {
                auditMsg =  "removeRecord failed:" + e;
                CMS.debug(method + ": " + auditMsg);
                throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_UPDATE_TOKENDB_FAILED);
            }
            continue;
        }
        CMS.debug(method + ": done for cuid:" + cuid);
    }


    protected void format(boolean skipAuth) throws TPSException, IOException {

        String auditMsg = null;
        String appletVersion = null;

        TPSSubsystem tps = (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);

        boolean isExternalReg = false;
        AppletInfo appletInfo = null;
        TokenRecord tokenRecord = null;
        try {
            appletInfo = getAppletInfo();
        } catch (TPSException e) {
            auditMsg = e.toString();
            tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg,
                    "failure");

            throw e;
        }
        appletInfo.setAid(getCardManagerAID());

        CMS.debug("TPSProcessor.format: token cuid: " + appletInfo.getCUIDhexStringPlain());
        boolean isTokenPresent = false;
        try {
            tokenRecord = tps.tdb.tdbGetTokenEntry(appletInfo.getCUIDhexStringPlain());
            // now the in memory tokenRecord is replaced by the actual token data
            CMS.debug("TPSProcessor.format: found token...");
            isTokenPresent = true;
        } catch (Exception e) {
            CMS.debug("TPSProcessor.format: token does not exist in tokendb... create one in memory");
            tokenRecord = new TokenRecord();
            tokenRecord.setId(appletInfo.getCUIDhexStringPlain());
        }

        fillTokenRecord(tokenRecord, appletInfo);
        session.setTokenRecord(tokenRecord);

        String cuid = appletInfo.getCUIDhexString();
        CMS.debug("TPSProcessor.format: CUID hex string=" + appletInfo.getCUIDhexStringPlain());
        //tokenRecord.setId(appletInfo.getCUIDhexString(true));
        String msn = appletInfo.getMSNString();

        byte major_version = appletInfo.getMajorVersion();
        byte minor_version = appletInfo.getMinorVersion();
        byte app_major_version = appletInfo.getAppMajorVersion();
        byte app_minor_version = appletInfo.getAppMinorVersion();

        CMS.debug("TPSProcessor.format: major_version " + major_version + " minor_version: " + minor_version
                + " app_major_version: " + app_major_version + " app_minor_version: " + app_minor_version);

        String tokenType = "tokenType";
        String resolverInstName = getResolverInstanceName();

        IAuthCredentials userCred =
                new com.netscape.certsrv.authentication.AuthCredentials();
        if (isExternalReg) {
            //ToDo, do some external Reg stuff along with authentication
            tokenType = "externalRegAddToToken";
        } else {
            CMS.debug("In TPSProcessor.format isExternalReg: OFF");
            /*
             * Note: op.format.tokenProfileResolver=none indicates no resolver
             *    plugin used (tokenType resolved perhaps via authentication)
             */

            try {
                tokenType = resolveTokenProfile(resolverInstName, cuid, msn, major_version, minor_version);
            } catch (TPSException e) {
                auditMsg = e.toString();
                tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg,
                        "failure");

                throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_MISCONFIGURATION);
            }
            CMS.debug("TPSProcessor.format: calculated tokenType: " + tokenType);
        }

        // isExternalReg : user already authenticated earlier
        if (!isExternalReg) {
            // authenticate per profile/tokenType configuration
            String configName = TPSEngine.OP_FORMAT_PREFIX + "." + tokenType + ".auth.enable";
            IConfigStore configStore = CMS.getConfigStore();
            boolean isAuthRequired;
            try {
                CMS.debug("TPSProcessor.format: getting config: " + configName);
                isAuthRequired = configStore.getBoolean(configName, true);
            } catch (EBaseException e) {
                CMS.debug("TPSProcessor.format: Internal Error obtaining mandatory config values. Error: " + e);
                auditMsg = "TPS error getting config values from config store." + e.toString();
                tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg,
                        "failure");

                throw new TPSException(auditMsg, TPSStatus.STATUS_ERROR_MISCONFIGURATION);
            }

            if (isAuthRequired && !skipAuth) {
                try {
                    TPSAuthenticator userAuth =
                            getAuthentication(TPSEngine.OP_FORMAT_PREFIX, tokenType);
                    userCred = requestUserId("format", cuid, userAuth, beginMsg.getExtensions());
                    userid = (String) userCred.get(userAuth.getAuthCredName());
                    CMS.debug("TPSProcessor.format: userCred (attempted) userid=" + userid);
                    // initialize userid first for logging purposes in case authentication fails
                    tokenRecord.setUserID(userid);
                    IAuthToken authToken = authenticateUser("format", userAuth, userCred);
                    userid = authToken.getInString("userid");
                    tokenRecord.setUserID(userid);
                    CMS.debug("TPSProcessor.format:: auth token userid=" + userid);
                    // TODO: should check if userid match?
                } catch (Exception e) {
                    // all exceptions are considered login failure
                    CMS.debug("TPSProcessor.format:: authentication exception thrown: " + e);
                    auditMsg = "authentication failed, status = STATUS_ERROR_LOGIN";

                    tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg,
                            "failure");

                    throw new TPSException(auditMsg,
                            TPSStatus.STATUS_ERROR_LOGIN);
                }
            } // TODO: if no auth required, should wipe out existing tokenRecord entry data later?
        }

        /**
         * TODO:
         * isExternalReg is not handled beyond this point until decided
         */

        //Now check provided profile

        checkProfileStateOK();

        if (isTokenPresent) {
            CMS.debug("TPSProcessor.format: token exists");
            TokenStatus newState = TokenStatus.UNINITIALIZED;
            // Check for transition to 0/UNINITIALIZED status.
            if (!tps.tdb.isTransitionAllowed(tokenRecord, newState)) {
                CMS.debug("TPSProcessor.format: token transition disallowed " +
                        tokenRecord.getTokenStatus() +
                        " to " + newState);
                auditMsg = "Operation for CUID " + appletInfo.getCUIDhexStringPlain() +
                        " Disabled, illegal transition attempted " + tokenRecord.getTokenStatus() +
                        " to " + newState;

                tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg,
                        "failure");

                throw new TPSException(auditMsg,
                        TPSStatus.STATUS_ERROR_DISABLED_TOKEN);
            } else {
                CMS.debug("TPSProcessor.format: token transition allowed " +
                        tokenRecord.getTokenStatus() +
                        " to " + newState);
            }
        } else {
            CMS.debug("TPSProcessor.format: token does not exist");

            checkAllowUnknownToken(TPSEngine.OP_FORMAT_PREFIX);
        }

        TPSBuffer build_id = getAppletVersion();

        if (build_id == null) {
            checkAllowNoAppletToken(TPSEngine.OP_FORMAT_PREFIX);
        } else {
            appletVersion = Integer.toHexString(app_major_version) + "." + Integer.toHexString(app_minor_version) + "."
                    + build_id.toHexString();
        }

        String appletRequiredVersion = checkForAppletUpgrade(TPSEngine.OP_FORMAT_PREFIX);

        CMS.debug("TPSProcessor.format: appletVersion found: " + appletVersion + " requiredVersion: "
                + appletRequiredVersion);

        SecurityLevel secLevel = SecurityLevel.SECURE_MSG_MAC_ENC;

        String tksConnId = getTKSConnectorID();

        upgradeApplet(TPSEngine.OP_FORMAT_PREFIX, appletRequiredVersion, secLevel,
                beginMsg.getExtensions(), tksConnId,
                10, 90);
        CMS.debug("TPSProcessor.format: Completed applet upgrade.");

        // Add issuer info to the token

        writeIssuerInfoToToken(null);

        if (requiresStatusUpdate()) {
            statusUpdate(100, "PROGRESS_DONE");
        }

        // Upgrade Symm Keys if needed

        SecureChannel channel = checkAndUpgradeSymKeys();
        channel.externalAuthenticate();
        tokenRecord.setKeyInfo(channel.getKeyInfoData().toHexStringPlain());

       if (isTokenPresent) {
            // Revoke certificates on token, if so configured
            try {
                revokeCertificates(tokenRecord.getId());
            } catch (TPSException te) {
                // failed revocation; capture message and continue
                auditMsg = te.getMessage();
            }
        }

        // Update Token DB
       tokenRecord.setStatus("uninitialized");
        try {
            tps.tdb.tdbUpdateTokenEntry(tokenRecord);
            String successMsg = "update token success";
            tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), successMsg,
                    "success");
        } catch (Exception e) {
            String failMsg = "update token failure";
            auditMsg = failMsg + ":" + e.toString();
            tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), failMsg,
                    "failure");

            throw new TPSException(auditMsg);
        }

        auditMsg = "format operation succeeded";

        tps.tdb.tdbActivity(ActivityDatabase.OP_FORMAT, tokenRecord, session.getIpAddress(), auditMsg, "success");

        CMS.debug("TPSProcessor.format:: ends");

    }

    protected void writeIssuerInfoToToken(SecureChannel origChannel) throws TPSException, IOException,
            UnsupportedEncodingException {
        if (checkIssuerInfoEnabled()) {

            SecurityLevel secLevel = SecurityLevel.SECURE_MSG_MAC_ENC;

            String tksConnId = getTKSConnectorID();

            int defKeyIndex = getChannelDefKeyIndex();
            int defKeyVersion = getChannelDefKeyVersion();

            SecureChannel channel = null;

            if (origChannel != null) {
                channel = origChannel;
            } else {

                channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, secLevel, tksConnId);
                channel.externalAuthenticate();

            }

            String issuer = getIssuerInfoValue();

            // We know this better be ASCII value URL.
            byte[] issuer_bytes = issuer.getBytes("US-ASCII");
            TPSBuffer issuerInfoBuff = new TPSBuffer(issuer_bytes);

            channel.setIssuerInfo(issuerInfoBuff);

        }
    }

    protected String getResolverInstanceName() throws TPSException {

        CMS.debug("TPSProcessor.getResolverInstanceName: entering for operaiton : " + currentTokenOperation);
        IConfigStore configStore = CMS.getConfigStore();
        String resolverInstName = null;

        String opPrefix = null;
        String opDefault = null;

        if (currentTokenOperation.equals(TPSEngine.FORMAT_OP)) {
            opPrefix = TPSEngine.OP_FORMAT_PREFIX;
            opDefault = TPSEngine.CFG_DEF_FORMAT_PROFILE_RESOLVER;

        } else if (currentTokenOperation.equals(TPSEngine.ENROLL_OP)) {
            opDefault = TPSEngine.CFG_DEF_ENROLL_PROFILE_RESOLVER;
            opPrefix = TPSEngine.OP_ENROLL_PREFIX;
        } else if (currentTokenOperation.equals(TPSEngine.PIN_RESET_OP)) {

            opDefault = TPSEngine.CFG_DEF_PIN_RESET_PROFILE_RESOLVER;
            opPrefix = TPSEngine.OP_PIN_RESET_PREFIX;
        }

        String config = opPrefix +
                "." + TPSEngine.CFG_PROFILE_RESOLVER;

        CMS.debug("TPSProcessor.getResolverInstanceName: config: " + config);
        try {
            resolverInstName = configStore.getString(config, opDefault);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getResolverInstanceName: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getResolverInstanceName: returning: " + resolverInstName);

        return resolverInstName;
    }

    /**
     * @param resolverInstName
     * @param cuid
     * @param msn
     * @param major_version
     * @param minor_version
     * @return
     */
    protected String resolveTokenProfile(
            String resolverInstName,
            String cuid,
            String msn,
            byte major_version,
            byte minor_version)
            throws TPSException {
        String tokenType;

        if (!resolverInstName.equals("none") && (selectedTokenType == null)) {

            try {
                TokenProfileParams pParams = new TokenProfileParams();
                CMS.debug("In TPSProcessor.resolveTokenProfile : after new TokenProfileParams");
                pParams.set(TokenProfileParams.PROFILE_PARAM_MAJOR_VERSION,
                        String.valueOf(major_version));
                pParams.set(TokenProfileParams.PROFILE_PARAM_MINOR_VERSION,
                        String.valueOf(minor_version));
                pParams.set(TokenProfileParams.PROFILE_PARAM_CUID, cuid);
                pParams.set(TokenProfileParams.PROFILE_PARAM_MSN, msn);
                if (beginMsg.getExtensions() != null) {
                    pParams.set(TokenProfileParams.PROFILE_PARAM_EXT_TOKEN_TYPE,
                            beginMsg.getExtensions().get("tokenType"));
                    pParams.set(TokenProfileParams.PROFILE_PARAM_EXT_TOKEN_ATR,
                            beginMsg.getExtensions().get("tokenATR"));
                }
                CMS.debug("In TPSProcessor.resolveTokenProfile : after setting TokenProfileParams");
                TPSSubsystem subsystem =
                        (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);
                BaseTokenProfileResolver resolverInst =
                        subsystem.getProfileResolverManager().getResolverInstance(resolverInstName);
                tokenType = resolverInst.getTokenType(pParams);
                CMS.debug("In TPSProcessor.resolveTokenProfile : profile resolver result: " + tokenType);
                setSelectedTokenType(tokenType);
            } catch (EBaseException et) {
                CMS.debug("In TPSProcessor.resolveTokenProfile exception:" + et);
                throw new TPSException("TPSProcessor.resolveTokenProfile failed.",
                        TPSStatus.STATUS_ERROR_DEFAULT_TOKENTYPE_NOT_FOUND);
            }

        } else {
            //Already have a token type, return it
            tokenType = getSelectedTokenType();
        }

        return tokenType;
    }

    protected String getIssuerInfoValue() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String info = null;

        String config = "op." + currentTokenOperation + "." + selectedTokenType + "." + TPSEngine.CFG_ISSUER_INFO_VALUE;

        CMS.debug("TPSProcessor.getIssuerInfoValue: config: " + config);
        try {
            info = configStore.getString(config, null);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getIssuerInfoValue: Internal error finding config value.");

        }

        if (info == null) {
            throw new TPSException("TPSProcessor.getIssuerInfoValue: Can't find issuer info value in the config.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.getIssuerInfoValue: returning: " + info);

        return info;
    }

    void checkProfileStateOK() throws TPSException {

        IConfigStore configStore = CMS.getConfigStore();

        String profileConfig = "config.Profiles." + selectedTokenType + ".state";
        String profileState = null;

        try {
            profileState = configStore.getString(profileConfig, TPSEngine.CFG_ENABLED);
        } catch (EBaseException e) {
            //Default TPSException will return a "contact admin" error code.
            throw new TPSException(
                    "TPSProcessor.checkProfileStateOK: internal error in getting profile state from config.");
        }

        if (!profileState.equals(TPSEngine.CFG_ENABLED)) {
            CMS.debug("TPSProcessor.checkProfileStateOK: profile specifically disabled.");
            throw new TPSException("TPSProcessor.checkProfileStateOK: profile disabled!");
        }

    }

    protected boolean checkIssuerInfoEnabled() throws TPSException {

        CMS.debug("TPSProcessor.checkIssuerEnabled entering...");

        IConfigStore configStore = CMS.getConfigStore();

        String issuerEnabledConfig = "op." + currentTokenOperation + "." + selectedTokenType + "."
                + TPSEngine.CFG_ISSUER_INFO_ENABLE;

        CMS.debug("TPSProcessor.checkIssuerEnabled config to check: " + issuerEnabledConfig);

        boolean issuerInfoEnabled = false;

        try {
            issuerInfoEnabled = configStore.getBoolean(issuerEnabledConfig, false);
        } catch (EBaseException e) {
            //Default TPSException will return a "contact admin" error code.
            throw new TPSException(
                    "TPSProcessor.checkIssuerInfo: internal error in getting value from config.");
        }

        CMS.debug("TPSProcessor.checkIssuerEnabled returning: " + issuerInfoEnabled);
        return issuerInfoEnabled;

    }

    //Obtain value and set class property.
    protected void checkIsExternalReg() throws TPSException {

        IConfigStore configStore = CMS.getConfigStore();
        String External_Reg_Cfg = TPSEngine.CFG_EXTERNAL_REG + "." + "enable";

        try {
            //These defaults are well known, it is safe to use them.

            CMS.debug("In TPS_Processor.checkIsExternalReg.");

            this.isExternalReg = configStore.getBoolean(External_Reg_Cfg, false);
            CMS.debug("In TPS_Processor.checkIsExternalReg. isExternalReg: " + isExternalReg);
        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.checkIsExternalReg: Internal Error obtaining mandatory config values. Error: "
                    + e1);
            throw new TPSException("TPS error getting config values from config store.");
        }

    }

    boolean checkServerSideKeyGen(String connId) throws TPSException {

        boolean result;
        IConfigStore configStore = CMS.getConfigStore();

        String profileConfig = "conn." + connId + "." + ".serverKeygen";

        try {
            result = configStore.getBoolean(profileConfig, false);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor: checkServerSideKeyGen: Internal error obtaining config value!");
        }

        return result;
    }

    void checkAllowNoAppletToken(String operation) throws TPSException {
        boolean allow = true;
        IConfigStore configStore = CMS.getConfigStore();

        String noAppletConfig = operation + "." + selectedTokenType + "." + TPSEngine.CFG_ALLOW_NO_APPLET;

        try {
            allow = configStore.getBoolean(noAppletConfig, true);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.checkAllowNoAppletToken: Internal error getting config param.");
        }

        if (!allow) {
            throw new TPSException("TPSProcessor.checkAllowNoAppletToken: token without applet not permitted!",
                    TPSStatus.STATUS_ERROR_CONTACT_ADMIN);
        }

    }

    boolean checkForAppletUpdateEnabled() throws TPSException {
        boolean enabled = false;

        IConfigStore configStore = CMS.getConfigStore();

        String appletUpdate = "op." + currentTokenOperation + "." + selectedTokenType + "."
                + TPSEngine.CFG_UPDATE_APPLET_ENABLE;
        CMS.debug("TPSProcessor.checkForAppletUpdateEnabled: getting config: " + appletUpdate);
        try {
            enabled = configStore.getBoolean(appletUpdate, false);
        } catch (EBaseException e) {
            throw new TPSException(
                    "TPSProcessor.checkForAppleUpdateEnabled: Can't find applet Update Enable. Internal error obtaining value.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }
        CMS.debug("TPSProcessor.checkForAppletUpdateEnabled: returning " + enabled);
        return enabled;
    }

    protected String checkForAppletUpgrade(String operation) throws TPSException {
        String requiredVersion = null;
        IConfigStore configStore = CMS.getConfigStore();

        String appletRequiredConfig = operation + "." + selectedTokenType + "."
                + TPSEngine.CFG_APPLET_UPDATE_REQUIRED_VERSION;
        CMS.debug("TPSProcessor.checkForAppletUpgrade: getting config: " + appletRequiredConfig);
        try {
            requiredVersion = configStore.getString(appletRequiredConfig, null);
        } catch (EBaseException e) {
            throw new TPSException(
                    "TPSProcessor.checkForAppletUpgrade: Can't find applet required Version. Internal error obtaining version.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        if (requiredVersion == null) {
            throw new TPSException("TPSProcessor.checkForAppletUpgrade: Can't find applet required Version.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("TPSProcessor.checkForAppletUpgrade: returning: " + requiredVersion);

        return requiredVersion;
    }

    protected void checkAllowUnknownToken(String operation) throws TPSException {
        boolean allow = true;

        IConfigStore configStore = CMS.getConfigStore();

        String unknownConfig = "op." + operation + "." + TPSEngine.CFG_ALLOW_UNKNOWN_TOKEN;

        try {
            allow = configStore.getBoolean(unknownConfig, true);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.checkAllowUnknownToken: Internal error getting config value.");
        }

        if (allow == false) {
            throw new TPSException(
                    "TPSProcessor.checkAllowUnknownToken: Unknown tokens not allowed for this operation!",
                    TPSStatus.STATUS_ERROR_TOKEN_DISABLED);
        }

    }

    protected String getTKSConnectorID() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String id = null;

        String config = "op." + currentTokenOperation + "." + selectedTokenType + ".tks.conn";

        try {
            id = configStore.getString(config, "tks1");
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getTKSConnectorID: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getTKSConectorID: returning: " + id);

        return id;
    }

    protected TPSBuffer getNetkeyAID() throws TPSException {

        String NetKeyAID = null;
        IConfigStore configStore = CMS.getConfigStore();
        try {

            NetKeyAID = configStore.getString(TPSEngine.CFG_APPLET_NETKEY_INSTANCE_AID,
                    TPSEngine.CFG_DEF_NETKEY_INSTANCE_AID);

        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.getNetkeyAID: Internal Error obtaining mandatory config values. Error: " + e1);
            throw new TPSException("TPS error getting config values from config store.");
        }

        TPSBuffer ret = new TPSBuffer(NetKeyAID);

        return ret;
    }

    protected TPSBuffer getNetkeyPAID() throws TPSException {

        String NetKeyPAID = null;
        IConfigStore configStore = CMS.getConfigStore();
        try {

            NetKeyPAID = configStore.getString(
                    TPSEngine.CFG_APPLET_NETKEY_FILE_AID, TPSEngine.CFG_DEF_NETKEY_FILE_AID);

        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.getNetkeyAID: Internal Error obtaining mandatory config values. Error: " + e1);
            throw new TPSException("TPS error getting config values from config store.");
        }

        TPSBuffer ret = new TPSBuffer(NetKeyPAID);

        return ret;
    }

    protected TPSBuffer getCardManagerAID() throws TPSException {

        String cardMgrAID = null;
        IConfigStore configStore = CMS.getConfigStore();
        try {

            cardMgrAID = configStore.getString(TPSEngine.CFG_DEF_CARDMGR_INSTANCE_AID,
                    TPSEngine.CFG_DEF_CARDMGR_INSTANCE_AID);

        } catch (EBaseException e1) {
            CMS.debug("TPS_Processor.getNetkeyAID: Internal Error obtaining mandatory config values. Error: " + e1);
            throw new TPSException("TPS error getting config values from config store.");
        }

        TPSBuffer ret = new TPSBuffer(cardMgrAID);

        return ret;
    }

    protected String getAppletExtension() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        String extension = null;
        String extensionConfig = TPSEngine.CFG_APPLET_EXTENSION;

        try {
            extension = configStore.getString(extensionConfig, "ijc");
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletExtension: Internal error finding config value.");

        }

        CMS.debug("TPSProcessor.getAppletExtension: returning: " + extension);

        return extension;
    }

    protected String getAppletDirectory(String operation) throws TPSException {

        IConfigStore configStore = CMS.getConfigStore();
        String directory = null;

        String directoryConfig = operation + "." + selectedTokenType + "." + TPSEngine.CFG_APPLET_DIRECTORY;

        //We need a directory
        try {
            directory = configStore.getString(directoryConfig);
        } catch (EPropertyNotFound e) {
            throw new TPSException("TPSProcessor.getAppletDirectory: Required config param missing.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletDirectory: Internal error finding config value.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }

        CMS.debug("getAppletDirectory: returning: " + directory);
        return directory;
    }

    protected int getChannelBlockSize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int blockSize = 0;
        try {
            blockSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_BLOCK_SIZE, TPSEngine.CFG_CHANNEL_DEF_BLOCK_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelBlockSize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcess.getChannelBlockSize: returning: " + blockSize);
        return blockSize;

    }

    protected int getChannelInstanceSize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int instanceSize = 0;
        try {
            instanceSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_INSTANCE_SIZE,
                    TPSEngine.CFG_CHANNEL_DEF_INSTANCE_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelInstanceSize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcess.getChannelInstanceSize: returning: " + instanceSize);

        return instanceSize;

    }

    protected int getAppletMemorySize() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int memSize = 0;
        try {
            memSize = configStore.getInteger(TPSEngine.CFG_CHANNEL_APPLET_MEMORY_SIZE,
                    TPSEngine.CFG_CHANNEL_DEF_APPLET_MEMORY_SIZE);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getAppletMemorySize: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }
        CMS.debug("TPSProcess.getAppletMemorySize: returning: " + memSize);

        return memSize;
    }

    protected int getChannelDefKeyVersion() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int ver = 0;
        try {
            ver = configStore.getInteger(TPSEngine.CFG_CHANNEL_DEFKEY_VERSION, 0x0);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelDefKeyVersion: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcessor.getChannelDefKeyVersion: " + ver);

        return ver;

    }

    protected int getChannelDefKeyIndex() throws TPSException {
        IConfigStore configStore = CMS.getConfigStore();
        int index = 0;
        try {
            index = configStore.getInteger(TPSEngine.CFG_CHANNEL_DEFKEY_INDEX, 0x0);

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getChannelDefKeyVersion: Internal error finding config value: " + e,
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);

        }

        CMS.debug("TPSProcessor.getChannelDefKeyIndex: " + index);

        return index;

    }

    protected PK11SymKey getSharedSecretTransportKey(String connId) throws TPSException, NotInitializedException {

        IConfigStore configStore = CMS.getConfigStore();
        String sharedSecretName = null;
        try {
            String configName = "conn." + connId + ".tksSharedSymKeyName";
            sharedSecretName = configStore.getString(configName, "sharedSecret");

        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getSharedSecretTransportKey: Internal error finding config value: "
                    + e,
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);

        }

        CMS.debug("TPSProcessor.getSharedSecretTransportKey: calculated key name: " + sharedSecretName);

        String symmKeys = null;
        boolean keyPresent = false;
        try {
            symmKeys = SessionKey.ListSymmetricKeys("internal");
            CMS.debug("TPSProcessor.getSharedSecretTransportKey: symmKeys List: " + symmKeys);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            CMS.debug(e);
        }

        for (String keyName : symmKeys.split(",")) {
            if (sharedSecretName.equals(keyName)) {
                CMS.debug("TPSProcessor.getSharedSecret: shared secret key found!");
                keyPresent = true;
                break;
            }

        }

        if (!keyPresent) {
            throw new TPSException("TPSProcessor.getSharedSecret: Can't find shared secret!",
                    TPSStatus.STATUS_ERROR_SECURE_CHANNEL);
        }

        // We know for now that shared secret is on this token
        String tokenName = "Internal Key Storage Token";
        PK11SymKey sharedSecret = SessionKey.GetSymKeyByName(tokenName, sharedSecretName);

        CMS.debug("TPSProcessor.getSharedSecret: SymKey returns: " + sharedSecret);

        return sharedSecret;

    }

    public boolean getIsExternalReg() {
        return isExternalReg;
    }

    public void process(BeginOpMsg beginMsg) throws TPSException, IOException {

        if (beginMsg == null) {
            throw new TPSException("TPSProcessor.process: invalid input data, not beginMsg provided.",
                    TPSStatus.STATUS_ERROR_UPGRADE_APPLET);
        }
        setBeginMessage(beginMsg);
        setCurrentTokenOperation("format");
        checkIsExternalReg();

        format(false);
    }

    public void statusUpdate(int status, String info) throws IOException {

        if (!requiresStatusUpdate())
            return;

        CMS.debug("In TPSProcessor.statusUpdate status: " + status + " info: " + info);

        StatusUpdateRequestMsg statusUpdate = new StatusUpdateRequestMsg(status, info);
        session.write(statusUpdate);

        //We don't really care about the response, just that we get it.

        session.read();

    }

    public TPSEngine getTPSEngine() {
        TPSSubsystem subsystem =
                (TPSSubsystem) CMS.getSubsystem(TPSSubsystem.ID);

        return subsystem.getEngine();

    }

    // Do the incoming extensions support status update?
    public boolean requiresStatusUpdate() {

        boolean result = false;

        // We can't get here without a begin message established.
        String update = getBeginMessage().getExtension(BeginOpMsg.STATUS_UPDATE_EXTENSION_NAME);

        if (update != null && update.equals("true")) {
            result = true;
        }

        return result;

    }

    protected AppletInfo getAppletInfo() throws TPSException, IOException {
        AppletInfo result = null;

        CMS.debug("TPSProcessor.getAppletInfo, entering ...");

        selectCardManager();

        TPSBuffer cplc_data = getCplcData();
        CMS.debug("cplc_data: " + cplc_data.toString());

        TPSBuffer token_cuid = extractTokenCUID(cplc_data);
        TPSBuffer token_msn = extractTokenMSN(cplc_data);

        /**
         * Checks if the netkey has the required applet version.
         */

        selectCoolKeyApplet();

        TPSBuffer token_status = getStatus();

        byte major_version = 0x0;
        byte minor_version = 0x0;
        byte app_major_version = 0x0;
        byte app_minor_version = 0x0;

        CMS.debug("TPS_Processor.getAppletInfo: status: " + token_status.toHexString());
        if (token_status.size() >= 4) {
            major_version = token_status.at(0);
            minor_version = token_status.at(1);
            app_major_version = token_status.at(2);
            app_minor_version = token_status.at(3);
        }

        int free_mem = 0;
        int total_mem = 0;

        if (token_status.size() >= 12) {
            byte tot_high = token_status.at(6);
            byte tot_low = token_status.at(7);

            byte free_high = token_status.at(10);
            byte free_low = token_status.at(11);

            total_mem = (tot_high << 8) + tot_low;
            free_mem = (free_high << 8) + free_low;

        }

        result = new AppletInfo(major_version, minor_version, app_major_version, app_minor_version);
        result.setCUID(token_cuid);
        result.setMSN(token_msn);
        result.setTotalMem(total_mem);
        result.setFreeMem(free_mem);

        CMS.debug("TPSProcessor.getAppletInfo: cuid: " + result.getCUIDhexString() + " msn: " + result.getMSNString()
                + " major version: " + result.getMinorVersion() + " minor version: " + result.getMinorVersion()
                + " App major version: " + result.getAppMajorVersion() + " App minor version: "
                + result.getAppMinorVersion());

        return result;
    }

    protected void selectCardManager() throws TPSException, IOException {
        CMS.debug("TPSProcessor.selectCardManager: entering..");
        TPSBuffer aidBuf = getCardManagerAID();

        APDUResponse select = selectApplet((byte) 0x04, (byte) 0x00, aidBuf);

        if (!select.checkResult()) {
            throw new TPSException("TPSProcessor.selectCardManager: Can't selelect the card manager applet!");
        }
    }

    protected boolean checkSymmetricKeysEnabled() throws TPSException {
        boolean result = true;

        IConfigStore configStore = CMS.getConfigStore();

        String symmConfig = "op" + "." + currentTokenOperation + "." + selectedTokenType + "."
                + TPSEngine.CFG_SYMM_KEY_UPGRADE_ENABLED;

        try {
            result = configStore.getBoolean(symmConfig, true);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.checkSymmetricKeysEnabled: Internal error getting config value.");
        }

        return result;
    }

    protected int getSymmetricKeysRequiredVersion() throws TPSException {
        int version = 0;
        ;

        IConfigStore configStore = CMS.getConfigStore();

        String requiredVersionConfig = "op" + "." + currentTokenOperation + "." + selectedTokenType + "."
                + "update.symmetricKeys.requiredVersion";

        CMS.debug("TPSProcessor.getSymmetricKeysRequiredVersion: configValue: " + requiredVersionConfig);
        try {
            version = configStore.getInteger(requiredVersionConfig, 0x0);
        } catch (EBaseException e) {
            throw new TPSException("TPSProcessor.getSymmetricKeysRequired: Internal error getting config value.");
        }

        CMS.debug("TPSProcessor.getSymmetricKeysRequiredVersion: returning version: " + version);

        return version;
    }

    protected SecureChannel checkAndUpgradeSymKeys() throws TPSException, IOException {

        /* If the key of the required version is
          not found, create them.

          This sends a InitializeUpdate request to the token.
          We tell the token to use whatever it thinks is the
          default key version (0). It will return the version
          of the key it actually used later. (This is accessed
          with GetKeyInfoData below)
          [ Note: This is not explained very well in the manual
            The token can have multiple sets of symmetric keys
            Each set is given a version number, which I think is
            better thought of as a SLOT. One key slot is populated
            with a set of keys when the token is manufactured.
            This is then designated as the default key set version.
            Later, we will write a new key set with PutKey, and
            set it to be the new default]
        */

        SecureChannel channel = null;

        int defKeyVersion = 0;
        int defKeyIndex = getChannelDefKeyIndex();

        if (checkSymmetricKeysEnabled()) {

            CMS.debug("TPSProcessor.checkAndUpgradeSymKeys: Symm key upgrade enabled.");
            int requiredVersion = getSymmetricKeysRequiredVersion();

            // try to make a secure channel with the 'requiredVersion' keys
            // If this fails, we know we will have to attempt an upgrade
            // of the keys

            boolean failed = false;
            try {

                channel = setupSecureChannel((byte) requiredVersion, (byte) defKeyIndex,
                        SecurityLevel.SECURE_MSG_MAC_ENC,
                        getTKSConnectorID());

            } catch (TPSException e) {

                CMS.debug("TPSProcessor.checkAndUpgradeSymKeys: failed to create secure channel with required version, we need to upgrade the keys.");
                failed = true;
            }

            //If we failed we need to upgrade the keys
            if (failed == true) {

                selectCardManager();

                channel = setupSecureChannel();

                /* Assemble the Buffer with the version information
                 The second byte is the key offset, which is always 1
                */

                byte[] nv = { (byte) requiredVersion, 0x01 };
                TPSBuffer newVersion = new TPSBuffer(nv);

                // GetKeyInfoData will return a buffer which is bytes 11,12 of
                // the data structure on page 89 of Cyberflex Access Programmer's
                // Guide
                // Byte 0 is the key set version.
                // Byte 1 is the index into that key set

                String connId = getTKSConnectorID();
                TPSBuffer curKeyInfo = channel.getKeyInfoData();
                TPSEngine engine = getTPSEngine();

                TPSBuffer keySetData = engine.createKeySetData(newVersion, curKeyInfo,
                        channel.getKeyDiversificationData(), connId);

                CMS.debug("TPSProcessor.checkAndUpgradeSymKeys: new keySetData from TKS: " + keySetData.toHexString());

                byte curVersion = curKeyInfo.at(0);
                byte curIndex = curKeyInfo.at(1);

                channel.putKeys(curVersion, curIndex, keySetData);

                String curVersionStr = curKeyInfo.toHexString();
                String newVersionStr = newVersion.toHexString();
                TPSSession session = getSession();
                TokenRecord tokenRecord = session.getTokenRecord();
                tokenRecord.setKeyInfo(newVersion.toHexStringPlain());

                CMS.debug("TPSProcessor.checkAndUpgradeSymKeys: curVersionStr: " + curVersionStr + " newVersionStr: "
                        + newVersionStr);

                selectCoolKeyApplet();

                channel = setupSecureChannel((byte) requiredVersion, (byte) defKeyIndex,
                        SecurityLevel.SECURE_MSG_MAC_ENC,
                        getTKSConnectorID());

            } else {
                CMS.debug("TPSProcessor.checkAndUpgradeSymeKeys: We are already at the desired key set, returning secure channel.");
            }

        } else {
            //Create a standard secure channel with current key set.
            CMS.debug("TPSProcessor.checkAndUpgradeSymKeys: Key changeover disabled in the configuration.");

            defKeyVersion = getChannelDefKeyVersion();

            channel = setupSecureChannel((byte) defKeyVersion, (byte) defKeyIndex, SecurityLevel.SECURE_MSG_MAC_ENC,
                    getTKSConnectorID());

        }

        CMS.debug("TPSProcessor.checkAndUpdradeSymKeys: Leaving successfully....");
        return channel;
    }

    //List objects that may be on a given token
    //Return null if object void of objects

    protected TPSBuffer listObjects(byte seq) throws TPSException, IOException {
        TPSBuffer objects = null;

        ListObjectsAPDU listObjects = new ListObjectsAPDU(seq);

        APDUResponse respApdu = handleAPDURequest(listObjects);

        if (!respApdu.checkResult()) {
            CMS.debug("TPSProcessor.listObjects: Bad response from ListObjects! Token possibly has no objects");
            return null;
        }

        objects = respApdu.getData();

        return objects;

    }

    // Request new pin from client
    protected String requestNewPin(int minLen, int maxLen) throws IOException, TPSException {

        CMS.debug("TPSProcessor.requestNewPin: entering...");

        String newPin = null;

        NewPinRequestMsg new_pin_req = new NewPinRequestMsg(minLen, maxLen);

        session.write(new_pin_req);

        NewPinResponseMsg new_pin_resp = (NewPinResponseMsg) session.read();

        newPin = new_pin_resp.get(NewPinResponseMsg.NEW_PIN_NAME);

        if (newPin.length() < minLen || newPin.length() > maxLen) {
            throw new TPSException("TPSProcessor.requestNewPin: new pin length outside of length contraints: min: "
                    + minLen + " max: " + maxLen);
        }

        return newPin;
    }

    protected String mapPattern(LinkedHashMap<String, String> map, String pattern) throws TPSException {

        //Right now only support one pattern to match within pattern: for instance:
        // "encryption key for $userid$ , not only the one "$userid$" pattern.

        String result = null;

        if (pattern == null || map == null) {
            throw new TPSException("TPSProcessor.mapPattern: Illegal input paramters!",
                    TPSStatus.STATUS_ERROR_CONTACT_ADMIN);
        }

        final char delim = '$';
        int firstPos = 0;
        int nextPos = 0;
        String patternToMap = null;
        String patternMapped = null;

        firstPos = pattern.indexOf(delim);
        nextPos = pattern.indexOf(delim, firstPos + 1);

        if ((nextPos - firstPos) <= 1) {
            return pattern;
        }

        patternToMap = pattern.substring(firstPos + 1, nextPos);

        CMS.debug("TPSProcessor.mapPattern: patternTo map: " + patternToMap);

        String piece1 = "";
        if (firstPos >= 1)
            piece1 = pattern.substring(0, firstPos);

        String piece2 = "";
        if (nextPos < (pattern.length() - 1))
            piece2 = pattern.substring(nextPos + 1);

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();

            String value = entry.getValue();
            CMS.debug("TPSProcessor.mapPattern: Exposed: key: " + key + " Param: " + value);

            if (key.equals(patternToMap)) {
                CMS.debug("TPSProcessor.mapPattern: found match: key: " + key + " mapped to: " + value);
                patternMapped = value;
                break;
            }

        }

        result = piece1 + patternMapped + piece2;

        CMS.debug("TPSProcessor.mapPattern: returning: " + result);
        return result;

    }

    protected String formatCurrentAppletVersion(AppletInfo aInfo) throws TPSException, IOException {

        if (aInfo == null) {
            throw new TPSException("TPSProcessor.formatCurrentAppletVersion: ", TPSStatus.STATUS_ERROR_CONTACT_ADMIN);
        }

        TPSBuffer build_id = getAppletVersion();
        String build_idStr = build_id.toHexStringPlain();

        String finalVersion = aInfo.getAppMajorVersion() + "." + aInfo.getAppMinorVersion() + "." + build_idStr;

        finalVersion = finalVersion.toLowerCase();
        CMS.debug("TPSProcessor.formatCurrentAppletVersion: returing: " + finalVersion);

        return finalVersion;

    }

    public static void main(String[] args) {
    }

}
