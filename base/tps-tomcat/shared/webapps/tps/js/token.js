/* --- BEGIN COPYRIGHT BLOCK ---
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Copyright (C) 2013 Red Hat, Inc.
 * All rights reserved.
 * --- END COPYRIGHT BLOCK ---
 *
 * @author Endi S. Dewata
 */

var TokenModel = Backbone.Model.extend({
    urlRoot: "/tps/rest/tokens"
});

var TokenCollection = Collection.extend({
    urlRoot: "/tps/rest/tokens",
    getEntries: function(response) {
        return response.Tokens.Token;
    },
    getLinks: function(response) {
        return response.Tokens.Link;
    },
    parseEntry: function(entry) {
        return new TokenModel({
            id: entry["@id"],
            userID: entry.UserID,
            status: entry.Status,
            reason: entry.Reason,
            appletID: entry.AppletID,
            keyInfo: entry.KeyInfo,
            created: entry.CreateTimestamp,
            modified: entry.ModifyTimestamp
        });
    }
});