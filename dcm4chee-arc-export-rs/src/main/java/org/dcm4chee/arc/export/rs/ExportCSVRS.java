/*
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2018
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 */

package org.dcm4chee.arc.export.rs;

import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Device;
import org.dcm4che3.util.StringUtils;
import org.dcm4che3.util.UIDUtils;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.conf.ExporterDescriptor;
import org.dcm4chee.arc.export.mgt.ExportManager;
import org.dcm4chee.arc.qmgt.HttpServletRequestInfo;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Nov 2018
 */
@RequestScoped
@Path("aets/{AETitle}")
public class ExportCSVRS {
    private static final Logger LOG = LoggerFactory.getLogger(ExportCSVRS.class);

    @Context
    private HttpServletRequest request;

    @Context
    private UriInfo uriInfo;

    @Inject
    private Device device;

    @Inject
    private ExportManager exportManager;

    @PathParam("AETitle")
    private String aet;

    @QueryParam("batchID")
    private String batchID;

    @HeaderParam("Content-Type")
    private MediaType contentType;

    private char csvDelimiter = ',';

    @POST
    @Path("/rs/studies/csv:{field}/export/{ExporterID}")
    @Consumes("text/csv")
    @Produces("application/json")
    public Response exportStudies(
            @PathParam("ExporterID") String exporterID,
            @PathParam("field") int field,
            InputStream in) {
        return exportStudiesFromCSV(exporterID, field, in);
    }

    @POST
    @Path("/export/{ExporterID}/studies/csv:{field}")
    @Consumes("text/csv")
    @Produces("application/json")
    public Response exportStudies1(
            @PathParam("ExporterID") String exporterID,
            @PathParam("field") int field,
            InputStream in) {
        return exportStudiesFromCSV(exporterID, field, in);
    }

    private Response exportStudiesFromCSV(String exporterID, int field, InputStream in) {
        logRequest();
        Response.Status status = Response.Status.BAD_REQUEST;
        try {
            if (field < 1)
                return errResponse(status,
                        "CSV field for Study Instance UID should be greater than or equal to 1");

            ApplicationEntity ae = device.getApplicationEntity(aet, true);
            if (ae == null || !ae.isInstalled())
                return errResponse(Response.Status.NOT_FOUND, "No such Application Entity: " + aet);

            ArchiveDeviceExtension arcDev = device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class);
            ExporterDescriptor exporter = arcDev.getExporterDescriptor(exporterID);
            if (exporter == null)
                return errResponse(Response.Status.NOT_FOUND, "No such Exporter: " + exporterID);

            if ("semicolon".equals(contentType.getParameters().get("delimiter")))
                csvDelimiter = ';';

            int count = 0;
            String warning = null;
            int csvUploadChunkSize = arcDev.getCSVUploadChunkSize();
            List<String> studyUIDs = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line = reader.readLine();
                while (line != null) {
                    if (line.chars().allMatch(Character::isWhitespace)) {
                        line = reader.readLine();
                        continue;
                    }

                    String studyUID = StringUtils.split(line, csvDelimiter)[field - 1].replaceAll("\"", "");
                    line = reader.readLine();
                    if (count == 0 && studyUID.chars().allMatch(Character::isLetter))
                        continue;

                    if (count > 0
                            || !arcDev.isValidateUID()
                            || validateUID(studyUID))
                        studyUIDs.add(studyUID);

                    if (studyUIDs.size() == csvUploadChunkSize || line == null) {
                        exportManager.scheduleStudyExportTasks(
                                exporter,
                                HttpServletRequestInfo.valueOf(request),
                                batchID,
                                studyUIDs.toArray(new String[0]));
                        count += studyUIDs.size();
                        studyUIDs.clear();
                    }
                }
                if (count == 0) {
                    warning = "Empty file or Incorrect field position or Not a CSV file or Invalid UIDs.";
                    status = Response.Status.NO_CONTENT;
                }

            } catch (QueueSizeLimitExceededException e) {
                status = Response.Status.SERVICE_UNAVAILABLE;
                warning = e.getMessage();
            } catch (Exception e) {
                warning = e.getMessage();
                status = Response.Status.INTERNAL_SERVER_ERROR;
            }

            if (warning == null && count > 0)
                return Response.accepted(count(count)).build();

            LOG.warn("Response {} caused by {}", status, warning);
            Response.ResponseBuilder builder = Response.status(status)
                    .header("Warning", warning);
            if (count > 0)
                builder.entity(count(count));
            return builder.build();
        } catch (Exception e) {
            return errResponseAsTextPlain(exceptionAsString(e), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean validateUID(String studyUID) {
        boolean valid = UIDUtils.isValid(studyUID);
        if (!valid)
            LOG.warn("Invalid UID in CSV file: " + studyUID);
        return valid;
    }

    private void logRequest() {
        LOG.info("Process {} {}?{} from {}@{}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getRemoteUser(),
                request.getRemoteHost());
    }

    private static String count(int count) {
        return "{\"count\":" + count + '}';
    }

    private Response errResponse(Response.Status status, String msg) {
        return errResponseAsTextPlain("{\"errorMessage\":\"" + msg + "\"}", status);
    }

    private Response errResponseAsTextPlain(String errorMsg, Response.Status status) {
        LOG.warn("Response {} caused by {}", status, errorMsg);
        return Response.status(status)
                .entity(errorMsg)
                .type("text/plain")
                .build();
    }

    private String exceptionAsString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}