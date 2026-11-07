# BE-06 native runtime

`./mvnw verify` prepares the resolved Maven runtime classpath during
`pre-integration-test`. `ProductTransactionNativeTestSupport` builds this Dockerfile
automatically from those exact dependency JARs and starts an isolated PostgreSQL
tenant. No manually built image, local dependency directory, or CI image override
is required.

The pinned multi-platform base supplies the JRE and container environment. Its
application classes/resources are excluded from the execution classpath. The
native test still verifies the provider and deployed plugin hashes, financial
rollback, durable audit records, and the known adjustment bypass.

Other integration tests retain their existing shared image selection, including
`fineract.it.image` / `FINERACT_IT_IMAGE`. BE-06 owns and stops only its containers
and network; server diagnostics are saved to `target/be06-fineract.log`.

Run the native scenario with the same lifecycle as CI:

```sh
./mvnw verify -Dspotless.check.skip=true -Dsurefire.skip=true \
  -Dit.test=ProductTransactionNativeHttpIntegrationTest
```
