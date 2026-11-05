####
# Image of the keychain generator: appends a new entry, a DEK and a pseudonymization key wrapped
# together by the HSM, to the keychain of notification.persistence.sealing.keychain. See README "Record keys".
#
# Build the distribution first, then the image:
#
# ./gradlew :keychain-generator:installDist
# docker build -f keychain-generator/src/main/docker/Dockerfile -t keychain-generator keychain-generator
#
# Configured by environment only (KEYCHAIN_OUTPUT_FILE, HSM_PROXY_KEK_ID for a new keychain, optionally
# HSM_PROXY_ENDPOINT, KEYCHAIN_INPUT_FILE, KEYCHAIN_ENTRY_ID, KEYCHAIN_ACTIVE_FROM and KEYCHAIN_EMERGENCY).
# The keychain is only written to KEYCHAIN_OUTPUT_FILE, so mount a directory the container user can write to.
###
FROM registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24

COPY --chown=185 build/install/keychain-generator/lib/ /deployments/lib/

USER 185

# Netty still allocates through sun.misc.Unsafe; allow it instead of warning on every run.
ENTRYPOINT [ "java", "--sun-misc-unsafe-memory-access=allow", "-cp", "/deployments/lib/*", "de.gematik.zeta.notificationservice.keychaingenerator.KeychainGeneratorKt" ]
