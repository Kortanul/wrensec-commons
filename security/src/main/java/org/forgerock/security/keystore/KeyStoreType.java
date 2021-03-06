/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.security.keystore;

/**
 * Lists the various keystore/truststore types.
 * @see <a href="http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore">
 *     List of Keystore Types in JDK 7</a>
 *
 * @deprecated  Specify the keystore type string directly in preference to this enum, as it is extensible at runtime
 */
@Deprecated()
public enum KeyStoreType {
    /** JKS keystore type. */
    JKS,
    /** JCEKS keystore type. */
    JCEKS,
    /** PKCS11 keystore type. */
    PKCS11,
    /** PKCS12 keystore type. */
    PKCS12
}
