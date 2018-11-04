#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail
export CROMWELL_BUILD_OPTIONAL_SECURE=true
# import in shellcheck / CI / IntelliJ compatible ways
# shellcheck source=/dev/null
source "${BASH_SOURCE%/*}/test.inc.sh" || source test.inc.sh

cromwell::build::setup_common_environment

CROMWELL_AKKA_TEST_TIME_FACTOR=1

case "${CROMWELL_BUILD_PROVIDER}" in
    "${CROMWELL_BUILD_PROVIDER_TRAVIS}")
        CROMWELL_SBT_TEST_EXCLUDE_TAGS="AwsTest,CromwellIntegrationTest,GcsIntegrationTest"
        ;;
    "${CROMWELL_BUILD_PROVIDER_JENKINS}")
        CROMWELL_SBT_TEST_EXCLUDE_TAGS="AwsTest,CromwellIntegrationTest,DockerTest,GcsIntegrationTest"
        CROMWELL_SBT_TEST_SPAN_SCALE_FACTOR=10
        CROMWELL_AKKA_TEST_TIME_FACTOR=10
        ;;
    *)
        # Use the full list of excludes listed in Testing.scala
        CROMWELL_SBT_TEST_EXCLUDE_TAGS=""
        ;;
esac
export CROMWELL_SBT_TEST_EXCLUDE_TAGS
export CROMWELL_SBT_TEST_SPAN_SCALE_FACTOR

sbt -Dakka.actor.debug.lifecycle=on -Dakka.loglevel="DEBUG" -Dakka.log-config-on-start=on -Dsbt.log.noformat=true -Dakka.test.timefactor=${CROMWELL_AKKA_TEST_TIME_FACTOR} -Dbackend.providers.Local.config.filesystems.local.localization.0=copy coverage test

cromwell::build::generate_code_coverage

cromwell::build::publish_artifacts
