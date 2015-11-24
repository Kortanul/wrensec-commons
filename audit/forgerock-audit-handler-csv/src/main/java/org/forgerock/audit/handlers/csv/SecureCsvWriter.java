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
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.audit.handlers.csv;

import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.forgerock.audit.handlers.csv.CsvSecureConstants.HEADER_HMAC;
import static org.forgerock.audit.handlers.csv.CsvSecureConstants.HEADER_SIGNATURE;
import static org.forgerock.audit.handlers.csv.CsvSecureConstants.SIGNATURE_ALGORITHM;
import static org.forgerock.audit.handlers.csv.CsvSecureUtils.dataToSign;
import static org.forgerock.util.Reject.checkNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.forgerock.audit.events.handlers.writers.RotatableWriter;
import org.forgerock.audit.events.handlers.writers.TextWriter;
import org.forgerock.audit.events.handlers.writers.TextWriterAdapter;
import org.forgerock.audit.rotation.RotationContext;
import org.forgerock.audit.rotation.RotationHooks;
import org.forgerock.audit.secure.SecureStorage;
import org.forgerock.audit.secure.SecureStorageException;
import org.forgerock.util.Reject;
import org.forgerock.util.annotations.VisibleForTesting;
import org.forgerock.util.encode.Base64;
import org.forgerock.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.supercsv.io.CsvMapReader;
import org.supercsv.io.ICsvMapReader;
import org.supercsv.prefs.CsvPreference;

/**
 * Responsible for writing to a CSV file; silently adds 2 last columns : HMAC and SIGNATURE.
 * The column HMAC is filled with the HMAC calculation of the current row and a key.
 * The column SIGNATURE is filled with the signature calculation of the last HMAC and the last signature if any.
 */
class SecureCsvWriter implements CsvWriter {

    private static final Logger logger = LoggerFactory.getLogger(SecureCsvWriter.class);

    private final CsvFormatter csvFormatter;
    private final String[] headers;
    private Writer csvWriter;
    private RotatableWriter rotatableWriter;

    private final HmacCalculator hmacCalculator;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock signatureLock = new ReentrantLock();
    private final Runnable signatureTask;
    private final SecureStorage secureStorage;
    private final Duration signatureInterval;
    private ScheduledFuture<?> scheduledSignature;

    private String lastHMAC;
    private byte[] lastSignature;

    SecureCsvWriter(File csvFile, String[] headers, CsvPreference csvPreference, SecureStorage secureStorage,
                      CsvAuditEventHandlerConfiguration config) throws IOException {
        Reject.ifFalse(config.getSecurity().isEnabled(), "SecureCsvWriter should only be used if security is enabled");
        boolean fileAlreadyInitialized = csvFile.exists();
        final CsvAuditEventHandlerConfiguration.CsvSecurity securityConfiguration = config.getSecurity();
        CsvSecureVerifier verifier = null;
        if (fileAlreadyInitialized) {
            // Run the CsvVerifier to check that the file was not tampered,
            // and get the headers and lastSignature for free
            try (ICsvMapReader reader = new CsvMapReader(new BufferedReader(new FileReader(csvFile)), csvPreference)) {
                final String[] actualHeaders;
                verifier = new CsvSecureVerifier(reader, secureStorage);
                if (!verifier.verify()) {
                    logger.info("The existing secure CSV file was tampered.");
                    throw new IOException("The CSV file was tampered.");
                } else {
                    logger.info("The existing secure CSV file was not tampered.");
                }
                actualHeaders = verifier.getHeaders();
                // Assert that the 2 headers equals.
                if (actualHeaders == null) {
                    fileAlreadyInitialized = false;
                } else {
                    if (actualHeaders.length != headers.length) {
                        throw new IOException("Resuming an existing CSV file but the headers do not match.");
                    }
                    for (int idx = 0; idx < actualHeaders.length; idx++) {
                        if (!actualHeaders[idx].equals(headers[idx])) {
                            throw new IOException("Resuming an existing CSV file but the headers do not match.");
                        }
                    }
                }
            }
        }
        this.headers = checkNotNull(headers, "The headers can't be null.");
        csvFormatter = new CsvFormatter(csvPreference);
        csvWriter = constructWriter(csvFile, fileAlreadyInitialized, config);

        this.secureStorage = secureStorage;
        this.signatureInterval = securityConfiguration.getSignatureIntervalDuration();

        try {
            SecretKey currentKey;
            if (fileAlreadyInitialized) {
                currentKey = secureStorage.readCurrentKey();
                if (currentKey == null) {
                    throw new IllegalStateException("We are supposed to resume but there is not entry for CurrentKey.");
                }
                logger.debug("Resuming the writer verifier with the key " + Base64.encode(currentKey.getEncoded()));
            } else {
                // Is it a fresh new keystore ?
                currentKey = secureStorage.readInitialKey();
                if (currentKey == null) {
                    throw new IllegalStateException("Expecting to find an initial key into the keystore.");
                }
                logger.debug("Starting the writer with the key " + Base64.encode(currentKey.getEncoded()));

                // As we start to work, store the current key too
                secureStorage.writeCurrentKey(currentKey);
            }
            this.hmacCalculator = new HmacCalculator(currentKey, CsvSecureConstants.HMAC_ALGORITHM);
        } catch (SecureStorageException e) {
            throw new RuntimeException(e);
        }

        if (rotatableWriter != null) {
            rotatableWriter.registerRotationHooks(new SecureCsvWriterRotationHooks());
        }

        scheduler = Executors.newScheduledThreadPool(1);

        signatureTask = new Runnable() {
            @Override
            public void run() {
                logger.info("Writing a signature.");

                try {
                    writeSignature();
                } catch (Exception ex) {
                    logger.error("An error occurred while writing the signature", ex);
                }
            }
        };

        if (fileAlreadyInitialized) {
            setLastHMAC(verifier.getLastHMAC());
            setLastSignature(verifier.getLastSignature());
        } else {
            writeHeader(headers);
            csvWriter.flush();
        }
    }

    private Writer constructWriter(File csvFile, boolean append, CsvAuditEventHandlerConfiguration config)
            throws IOException {
        TextWriter textWriter;
        if (config.getFileRotation().isRotationEnabled()) {
            rotatableWriter = new RotatableWriter(csvFile, config, append);
            textWriter = rotatableWriter;
        } else {
            textWriter = new TextWriter.Stream(new FileOutputStream(csvFile, append));
        }

        if (config.getBuffering().isEnabled()) {
            logger.warn("Secure CSV logging does not support buffering. Buffering config will be ignored.");
        }
        return new TextWriterAdapter(textWriter);
    }

    @Override
    public void flush() throws IOException {
        csvWriter.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        scheduler.shutdown();
        try {
            while (!scheduler.awaitTermination(500, MILLISECONDS)) {
                logger.debug("Waiting to terminate the scheduler.");
            }
        } catch (InterruptedException ex) {
            logger.error("Unable to terminate the scheduler", ex);
            Thread.currentThread().interrupt();
        }
        signatureLock.lock();
        try {
            forceWriteSignature();
        } finally {
            signatureLock.unlock();
        }
        csvWriter.close();
    }

    private void forceWriteSignature() throws IOException {
        if (scheduledSignature != null && scheduledSignature.cancel(false)) {
            // We were able to cancel it before it starts, so let's generate the signature now.
            writeSignature();
        }
    }

    public void writeHeader(String... header) throws IOException {
        writeHeader(csvWriter, header);
    }

    public void writeHeader(Writer writer, String... header) throws IOException {
        String[] newHeader = addExtraColumns(header);
        writer.write(csvFormatter.formatHeader(newHeader));
    }

    @VisibleForTesting
    void writeSignature() throws IOException {
        // We have to prevent from writing another line between the signature calculation
        // and the signature's row write, as the calculation uses the lastHMAC.
        signatureLock.lock();
        try {
            lastSignature = secureStorage.sign(dataToSign(lastSignature, lastHMAC));
            Map<String, String> values = singletonMap(HEADER_SIGNATURE, Base64.encode(lastSignature));
            logger.info("Writing signature :" + lastSignature);
            writeEvent(values);

            // Store the current signature into the Keystore
            secureStorage.writeCurrentSignatureKey(new SecretKeySpec(lastSignature, SIGNATURE_ALGORITHM));
        } catch (SecureStorageException ex) {
            logger.error(ex.getMessage(), ex);
            throw new IOException(ex);
        } finally {
            signatureLock.unlock();
            flush();
        }
    }

    /**
     * Forces rotation of the writer.
     * <p>
     * Rotation is possible only if file rotation is enabled.
     *
     * @return {@code true} if rotation was done, {@code false} otherwise.
     * @throws IOException
     *          If an error occurs
     */
    public boolean forceRotation() throws IOException {
        return rotatableWriter != null ? rotatableWriter.forceRotation() : false;
    }

    /**
     * Write a row into the CSV files.
     * @param values The keys of the {@link Map} have to match the column's header.
     * @throws IOException
     */
    public void writeEvent(Map<String, String> values) throws IOException {
        writeEvent(csvWriter, values);
    }

    /**
     * Write a row into the CSV files.
     * @param values The keys of the {@link Map} have to match the column's header.
     * @throws IOException
     */
    public void writeEvent(Writer writer, Map<String, String> values) throws IOException {
        signatureLock.lock();
        try {
            logger.info("Writing data : " + values + " for " + Arrays.toString(headers));
            String[] extendedHeaders = addExtraColumns(headers);

            Map<String, String> extendedValues = new HashMap<>(values);
            if (!values.containsKey(CsvSecureConstants.HEADER_SIGNATURE)) {
                insertHMACSignature(extendedValues, headers);
            }

            writer.write(csvFormatter.formatEvent(extendedValues, extendedHeaders));
            writer.flush();
            // Store the current key
            secureStorage.writeCurrentKey(hmacCalculator.getCurrentKey());

            // Schedule a signature task only if needed.
            if (!values.containsKey(HEADER_SIGNATURE)
                    && (scheduledSignature == null || scheduledSignature.isDone())) {
                logger.info("Triggering a new signature task to be executed in " + signatureInterval);
                try {
                    scheduledSignature = scheduler.schedule(signatureTask, signatureInterval.getValue(),
                            signatureInterval.getUnit());
                } catch (RejectedExecutionException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        } catch (SecureStorageException ex) {
            throw new IOException(ex);
        } finally {
            signatureLock.unlock();
        }
    }

    private void insertHMACSignature(Map<String, String> values, String[] nameMapping) throws IOException {
        try {
            lastHMAC = hmacCalculator.calculate(dataToSign(logger, values, nameMapping));
            values.put(CsvSecureConstants.HEADER_HMAC, lastHMAC);
        } catch (SignatureException ex) {
            logger.error(ex.getMessage(), ex);
            throw new IOException(ex);
        }
    }

    private String[] addExtraColumns(String... header) {
        String[] newHeader = new String[header.length + 2];
        System.arraycopy(header, 0, newHeader, 0, header.length);
        newHeader[header.length] = HEADER_HMAC;
        newHeader[header.length + 1] = HEADER_SIGNATURE;
        return newHeader;
    }

    void setLastHMAC(String lastHMac) {
        this.lastHMAC = lastHMac;
    }

    void setLastSignature(byte[] lastSignature) {
        this.lastSignature = lastSignature;
    }

    void writeLastSignature(Writer writer) throws IOException {
        // We have to prevent from writing another line between the signature calculation
        // and the signature's row write, as the calculation uses the lastHMAC.
        signatureLock.lock();
        try {
            Map<String, String> values = singletonMap(HEADER_SIGNATURE, Base64.encode(lastSignature));
            writeEvent(writer, values);
        } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            throw new IOException(ex);
        } finally {
            signatureLock.unlock();
        }
    }

    private class SecureCsvWriterRotationHooks implements RotationHooks {

        @Override
        public void preRotationAction(RotationContext context) throws IOException {
            // ensure the final signature is written
            forceWriteSignature();
        }

        @Override
        public void postRotationAction(RotationContext context) throws IOException {
            Writer writer = (Writer) context.getAttribute("writer");
            // ensure the signature chaining along the files
            writeHeader(writer, headers);
            writeLastSignature(writer);
            writer.flush();
        }
    }
}
