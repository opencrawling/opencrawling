/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.authorization.openfga;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFgaIdentifiersTest {

    /** OpenFGA object id rule: type:id, no whitespace, at most 256 characters, id without # : @. */
    private static final Pattern OBJECT = Pattern.compile("^[a-z]+:[^\\s#:@]{1,250}$");

    @Test
    void documentsAreHashedToAFixedLengthObject() {
        String object = OpenFgaIdentifiers.documentObject("file:///shares/Finance Q3 #1.pdf");
        assertThat(object).matches("document:[0-9a-f]{64}");
        assertThat(object).isEqualTo(OpenFgaIdentifiers.documentObject("file:///shares/Finance Q3 #1.pdf"));
        assertThat(object).isNotEqualTo(OpenFgaIdentifiers.documentObject("file:///shares/finance q3 #1.pdf"));
    }

    @Test
    void identitiesAreLowerCasedAndEscaped() {
        assertThat(OpenFgaIdentifiers.groupNameGroup("Finance")).isEqualTo("group:g_finance");
        assertThat(OpenFgaIdentifiers.groupNameGroup("DOMAIN\\Jane Doe@corp.com#1"))
                .isEqualTo("group:g_domain%5Cjane%20doe%40corp.com%231");
        assertThat(OpenFgaIdentifiers.groupNameGroup("100%")).isEqualTo("group:g_100%25");
        assertThat(OpenFgaIdentifiers.groupNameGroup("Zoë")).isEqualTo("group:g_zo%C3%AB");
        for (String identity : new String[] {"a b", "x:y", "p#q", "u@v", "tab\there", "%", "group:Finance"}) {
            assertThat(OpenFgaIdentifiers.groupNameGroup(identity)).matches(OBJECT);
            assertThat(OpenFgaIdentifiers.userObject(identity)).matches(OBJECT);
        }
    }

    @Test
    void longIdentitiesAreHashedUnambiguously() {
        String longIdentity = "x".repeat(OpenFgaIdentifiers.MAX_ENCODED_LENGTH + 1);
        String group = OpenFgaIdentifiers.groupNameGroup(longIdentity);
        assertThat(group).matches("group:g_%h[0-9a-f]{64}").matches(OBJECT);
        assertThat(OpenFgaIdentifiers.groupNameGroup("x".repeat(OpenFgaIdentifiers.MAX_ENCODED_LENGTH)))
                .isEqualTo("group:g_" + "x".repeat(OpenFgaIdentifiers.MAX_ENCODED_LENGTH));
        // "%h..." cannot come out of the encoder for a short identity: a literal % is always escaped.
        assertThat(OpenFgaIdentifiers.groupNameGroup("%h" + "0".repeat(64))).startsWith("group:g_%25h");
    }

    @Test
    void anEntrysPrefixDecidesWhetherItHoldsUsersGroupsOrBoth() {
        assertThat(OpenFgaIdentifiers.aclEntryGroups("group:Finance")).containsExactly("group:g_finance");
        assertThat(OpenFgaIdentifiers.aclEntryGroups("USER:alice")).containsExactly("group:u_alice");
        assertThat(OpenFgaIdentifiers.aclEntryGroups(" finance ")).containsExactly("group:u_finance", "group:g_finance");
        // Only the first prefix is the kind; the rest is the name.
        assertThat(OpenFgaIdentifiers.aclEntryGroups("user:group:x")).containsExactly("group:u_group%3Ax");
        // A principal only loses user:, a role only loses group:.
        assertThat(OpenFgaIdentifiers.principalGroup("User:Alice")).isEqualTo("group:u_alice");
        assertThat(OpenFgaIdentifiers.principalGroup("group:alice")).isEqualTo("group:u_group%3Aalice");
        assertThat(OpenFgaIdentifiers.roleGroup("Group:Finance")).isEqualTo("group:g_finance");
        assertThat(OpenFgaIdentifiers.roleGroup("user:finance")).isEqualTo("group:g_user%3Afinance");
    }

    @Test
    void aUserAndAGroupWithTheSameNameNeverShareAGroup() {
        assertThat(OpenFgaIdentifiers.aclEntryGroups("group:finance")).doesNotContain(OpenFgaIdentifiers.principalGroup("finance"));
        assertThat(OpenFgaIdentifiers.aclEntryGroups("user:alice")).doesNotContain(OpenFgaIdentifiers.roleGroup("alice"));
    }

    @Test
    void caseFoldingMatchesEqualsIgnoreCase() {
        String[][] pairs = {{"IŞIK", "ışık"}, {"ſtrasse", "STRASSE"}, {"Σοφία", "σοφίΑ"}, {"ς", "Σ"}, {"µ", "μ"},
                {"K", "\u212A"}, {"İ", "i"}, {"İ", "i\u0307"}, {"Straße", "STRASSE"}, {"alice", "ALICE"}};
        for (String[] pair : pairs) {
            assertThat(OpenFgaIdentifiers.groupNameGroup(pair[0]).equals(OpenFgaIdentifiers.groupNameGroup(pair[1])))
                    .as("%s vs %s", pair[0], pair[1]).isEqualTo(pair[0].equalsIgnoreCase(pair[1]));
        }
    }

    @Test
    void documentIdsIgnoreSurroundingWhitespace() {
        assertThat(OpenFgaIdentifiers.documentObject(" doc-1 ")).isEqualTo(OpenFgaIdentifiers.documentObject("doc-1"));
    }

    @Test
    void anonymousCallersHaveTheirOwnUser() {
        assertThat(OpenFgaIdentifiers.userObject(null)).isEqualTo("user:anonymous");
        assertThat(OpenFgaIdentifiers.userObject(" ")).isEqualTo("user:anonymous");
        assertThat(OpenFgaIdentifiers.userObject("anonymous")).isEqualTo("user:p_anonymous");
    }
}
