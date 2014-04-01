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

package org.dogtagpki.server.tps.rest;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.security.Principal;
import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.dogtagpki.server.tps.TPSSubsystem;
import org.dogtagpki.server.tps.config.ProfileMappingDatabase;
import org.dogtagpki.server.tps.config.ProfileMappingRecord;
import org.jboss.resteasy.plugins.providers.atom.Link;

import com.netscape.certsrv.apps.CMS;
import com.netscape.certsrv.base.BadRequestException;
import com.netscape.certsrv.base.ForbiddenException;
import com.netscape.certsrv.base.PKIException;
import com.netscape.certsrv.tps.profile.ProfileMappingCollection;
import com.netscape.certsrv.tps.profile.ProfileMappingData;
import com.netscape.certsrv.tps.profile.ProfileMappingResource;
import com.netscape.cms.servlet.base.PKIService;

/**
 * @author Endi S. Dewata
 */
public class ProfileMappingService extends PKIService implements ProfileMappingResource {

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpHeaders headers;

    @Context
    private Request request;

    @Context
    private HttpServletRequest servletRequest;

    public final static int DEFAULT_SIZE = 20;

    public ProfileMappingService() {
        CMS.debug("ProfileMappingService.<init>()");
    }

    public ProfileMappingData createProfileMappingData(ProfileMappingRecord profileMappingRecord) throws UnsupportedEncodingException {

        String profileMappingID = profileMappingRecord.getID();

        ProfileMappingData profileMappingData = new ProfileMappingData();
        profileMappingData.setID(profileMappingID);
        profileMappingData.setStatus(profileMappingRecord.getStatus());
        profileMappingData.setProperties(profileMappingRecord.getProperties());

        profileMappingID = URLEncoder.encode(profileMappingID, "UTF-8");
        URI uri = uriInfo.getBaseUriBuilder().path(ProfileMappingResource.class).path("{profileMappingID}").build(profileMappingID);
        profileMappingData.setLink(new Link("self", uri));

        return profileMappingData;
    }

    public ProfileMappingRecord createProfileMappingRecord(ProfileMappingData profileMappingData) {

        ProfileMappingRecord profileMappingRecord = new ProfileMappingRecord();
        profileMappingRecord.setID(profileMappingData.getID());
        profileMappingRecord.setStatus(profileMappingData.getStatus());
        profileMappingRecord.setProperties(profileMappingData.getProperties());

        return profileMappingRecord;
    }

    @Override
    public Response findProfileMappings(String filter, Integer start, Integer size) {

        CMS.debug("ProfileMappingService.findProfileMappings()");

        try {
            start = start == null ? 0 : start;
            size = size == null ? DEFAULT_SIZE : size;

            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            Iterator<ProfileMappingRecord> profileMappings = database.findRecords(filter).iterator();

            ProfileMappingCollection response = new ProfileMappingCollection();
            int i = 0;

            // skip to the start of the page
            for ( ; i<start && profileMappings.hasNext(); i++) profileMappings.next();

            // return entries up to the page size
            for ( ; i<start+size && profileMappings.hasNext(); i++) {
                response.addEntry(createProfileMappingData(profileMappings.next()));
            }

            // count the total entries
            for ( ; profileMappings.hasNext(); i++) profileMappings.next();
            response.setTotal(i);

            if (start > 0) {
                URI uri = uriInfo.getRequestUriBuilder().replaceQueryParam("start", Math.max(start-size, 0)).build();
                response.addLink(new Link("prev", uri));
            }

            if (start+size < i) {
                URI uri = uriInfo.getRequestUriBuilder().replaceQueryParam("start", start+size).build();
                response.addLink(new Link("next", uri));
            }

            return createOKResponse(response);

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response getProfileMapping(String profileMappingID) {

        CMS.debug("ProfileMappingService.getProfileMapping(\"" + profileMappingID + "\")");

        try {
            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            return createOKResponse(createProfileMappingData(database.getRecord(profileMappingID)));

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response addProfileMapping(ProfileMappingData profileMappingData) {

        CMS.debug("ProfileMappingService.addProfileMapping(\"" + profileMappingData.getID() + "\")");

        try {
            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            database.addRecord(profileMappingData.getID(), createProfileMappingRecord(profileMappingData));
            profileMappingData = createProfileMappingData(database.getRecord(profileMappingData.getID()));

            return createCreatedResponse(profileMappingData, profileMappingData.getLink().getHref());

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response updateProfileMapping(String profileMappingID, ProfileMappingData profileMappingData) {

        CMS.debug("ProfileMappingService.updateProfileMapping(\"" + profileMappingID + "\")");

        try {
            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            ProfileMappingRecord record = database.getRecord(profileMappingID);

            String status = record.getStatus();
            if (!"Disabled".equals(status)) {
                throw new ForbiddenException("Unable to update profile mapping " + profileMappingID);
            }

            status = profileMappingData.getStatus();
            if (!"Enabled".equals(status)) {
                throw new ForbiddenException("Invalid profile mapping status: " + status);
            }

            Principal principal = servletRequest.getUserPrincipal();
            if (database.requiresApproval() && !database.canApprove(principal)) {
                status = "Pending_Approval";
            }

            record.setStatus(status);
            record.setProperties(profileMappingData.getProperties());
            database.updateRecord(profileMappingID, record);

            profileMappingData = createProfileMappingData(database.getRecord(profileMappingID));

            return createOKResponse(profileMappingData);

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response changeProfileMappingStatus(String profileMappingID, String action) {

        if (profileMappingID == null) throw new BadRequestException("Profile mapping ID is null.");
        if (action == null) throw new BadRequestException("Action is null.");

        CMS.debug("ProfileMappingService.changeProfileMappingStatus(\"" + profileMappingID + "\")");

        try {
            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            ProfileMappingRecord record = database.getRecord(profileMappingID);
            String status = record.getStatus();

            if ("Disabled".equals(status)) {
                if ("enable".equals(action)) {
                    status = "Enabled";
                } else {
                    throw new BadRequestException("Invalid action: " + action);
                }

            } else if ("Enabled".equals(status)) {
                if ("disable".equals(action)) {
                    status = "Disabled";
                } else {
                    throw new BadRequestException("Invalid action: " + action);
                }

            } else if ("Pending_Approval".equals(status)) {
                if ("approve".equals(action)) {
                    status = "Enabled";
                } else if ("reject".equals(action)) {
                    status = "Disabled";
                } else {
                    throw new BadRequestException("Invalid action: " + action);
                }

            } else {
                throw new PKIException("Invalid profile mapping status: " + status);
            }

            record.setStatus(status);
            database.updateRecord(profileMappingID, record);

            ProfileMappingData profileMappingData = createProfileMappingData(database.getRecord(profileMappingID));

            return createOKResponse(profileMappingData);

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }

    @Override
    public Response removeProfileMapping(String profileMappingID) {

        CMS.debug("ProfileMappingService.removeProfileMapping(\"" + profileMappingID + "\")");

        try {
            TPSSubsystem subsystem = (TPSSubsystem)CMS.getSubsystem(TPSSubsystem.ID);
            ProfileMappingDatabase database = subsystem.getProfileMappingDatabase();

            ProfileMappingRecord record = database.getRecord(profileMappingID);
            String status = record.getStatus();

            if (!"Disabled".equals(status)) {
                throw new ForbiddenException("Unable to delete profile mapping " + profileMappingID);
            }

            database.removeRecord(profileMappingID);

            return createNoContentResponse();

        } catch (PKIException e) {
            throw e;

        } catch (Exception e) {
            e.printStackTrace();
            throw new PKIException(e.getMessage());
        }
    }
}