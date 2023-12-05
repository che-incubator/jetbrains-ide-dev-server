# IDEA dev server for Che [![Dev](https://img.shields.io/static/v1?label=Open%20in&message=Che%20dogfooding%20server%20&logo=eclipseche&color=FDB940&labelColor=525C86)](https://che-dogfooding.apps.che-dev.x6e0.p1.openshiftapps.com/f?url=https://github.com/che-samples/web-nodejs-sample&che-editor=https://eclipse-che.github.io/che-plugin-registry/main/v3/plugins/che-incubator/che-idea-server/next/devfile.yaml&policies.create=peruser) [![Dev](https://img.shields.io/static/v1?label=Open%20in&message=DevSandbox%20(hosted%20by%20Red%20Hat)&logo=eclipseche&color=FDB940&labelColor=525C86)](https://devspaces.apps.sandbox-m4.g2pi.p1.openshiftapps.com/f?url=https://github.com/che-samples/web-nodejs-sample&che-editor=https://eclipse-che.github.io/che-plugin-registry/main/v3/plugins/che-incubator/che-idea-server/next/devfile.yaml&policies.create=peruser)

## Built images

The image built from the `main` branch is published to [quay.io/che-incubator/che-idea-dev-server:next](https://quay.io/repository/che-incubator/che-idea-dev-server?tab=tags).

## Image build

`podman build . -f build/dockerfiles/Dockerfile -t che-idea-dev-server:next`

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
