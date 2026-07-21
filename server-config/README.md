# Server setup (JBoss EAP 7.4)

Everything else in this project is bundled inside the EAR. The one thing
that has to exist on the server beforehand is the datasource the process
engine's `JtaProcessEngineConfiguration` looks up via JNDI.

1. **Create the datasource** (installs the H2 driver as a module, then adds
   the `ProcessEngine` datasource):
   ```sh
   $JBOSS_HOME/bin/jboss-cli.sh --connect --file=add-datasource.cli
   ```
   Edit `H2_JAR_PATH`/`H2_VERSION` in the script first, or swap in a real
   (ideally XA) datasource for anything beyond a local demo.

2. **Verify it's bound:**
   ```sh
   $JBOSS_HOME/bin/jboss-cli.sh --connect \
     --command="/subsystem=datasources/data-source=ProcessEngine:read-resource"
   ```

3. **Deploy the EAR** built from the project root:
   ```sh
   cp ../ear/target/camunda-demo.ear $JBOSS_HOME/standalone/deployments/
   ```

See the top-level `README.md` for how to exercise and verify the deployed
demo, including the job-executor thread pool check.