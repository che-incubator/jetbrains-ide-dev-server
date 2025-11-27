# JetBrains IDE development server for Eclipse Che
[![Dev](https://img.shields.io/static/v1?label=Open%20in&message=Che%20dogfooding%20server%20&logo=eclipseche&color=FDB940&labelColor=525C86)](https://che-dogfooding.apps.che-dev.x6e0.p1.openshiftapps.com/f?url=https://github.com/che-samples/web-nodejs-sample&che-editor=che-incubator/che-idea-server/next&policies.create=peruser) [![Dev](https://img.shields.io/static/v1?label=Open%20in&message=DevSandbox%20(hosted%20by%20Red%20Hat)&logo=eclipseche&color=FDB940&labelColor=525C86)](https://workspaces.openshift.com/f?url=https://github.com/che-samples/web-nodejs-sample&che-editor=https://eclipse-che.github.io/che-plugin-registry/main/v3/plugins/che-incubator/che-idea-server/next/devfile.yaml&policies.create=peruser)

This project enables running a JetBrains IDE as a remote development server in Eclipse Che CDE.

Check out [JetBrains Gateway plugin](https://github.com/redhat-developer/devspaces-gateway-plugin/) to connect your local JetBrains IDE to a remote CDE.

## Feedback
We love to hear from users and developers.

You can ask questions, report bugs, and request features using [GitHub issues](https://github.com/eclipse/che/issues) in the Eclipse Che main repository.

## Images

To build a container image:
```bash
podman build . -f build/dockerfiles/Dockerfile -t che-ide-dev-server:next
```

[quay.io/che-incubator/che-idea-dev-server:next](https://quay.io/repository/che-incubator/che-idea-dev-server?tab=tags) is built and published each time a new change is merged to the `main` branch.

[quay.io/che-incubator/che-idea-dev-server:latest](https://quay.io/repository/che-incubator/che-idea-dev-server?tab=tags) is built and published for each new project release.

## Development
To test the changes:

1. Build

a. If you need to test the changes made to the `che-integration-plugin`, rebuild the plugin:
```bash
cd che-integration-plugin && ./gradlew clean buildPlugin
```

b. Build the image and upload it to a registry (from the project root folder):
```bash
podman build . -f build/dockerfiles/Dockerfile -t che-idea-dev-server:next --arch amd64
podman tag localhost/che-idea-dev-server:next quay.io/azatsary/che-idea-dev-server:next
podman push quay.io/azatsary/che-idea-dev-server:next
```

2. Create a Gist with the editor definition, like [this](https://gist.githubusercontent.com/azatsarynnyy/692c7f8b795cbc2adfc745f65a5939ff/raw/1d550a4f59143a07b7df0ee19c0dbed6ff4f3fc8/che-idea-server.yaml) with setting the right image and required IDE in the following section:
```yaml
...
- name: editor-injector
    container:
      image: quay.io/azatsary/che-idea-dev-server:next
      command: ['/entrypoint-init-container.sh', 'webstorm']
...
```

3. Spin up a workspace, providing the created Gist link to the Dashboard:
<img width="984" height="511" alt="image" src="https://github.com/user-attachments/assets/46d4a593-ae8e-4d63-8841-4725a23e53b8" />

4. For iterating, repeat step 1 and restart the workspace.

## Troubleshooting
Integration of Jet Brains Gateway with Eclipse Che this editor provides is in the early stage. So, you may face some problems that require a manual resolution.

**Problem 1:** executing `oc port-forward ...` command reports an error:
```
error: error upgrading connection: pods "..." is forbidden: User "..." cannot create resource "pods/portforward" in API group "" in the namespace "..."
```
**Solution:** ask the admin to allow port forwarding for your user.

**Problem 2:** when typing in the editor, a popup reports an error:
```
No internet connection
```
**Solution:** check your terminal if the port forwarding is enabled.

**Problem 3:** executing `oc port-forward ...` command reports an error:
`Error from server (NotFound): pods "..." not found`

**Solution:** check the Dashboard that your workspace is running.

## License

- [Eclipse Public License 2.0](LICENSE)

## Trademark

"Che" is a trademark of the Eclipse Foundation.
