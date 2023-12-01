# IDEA dev server for Che [![Dev](https://img.shields.io/static/v1?label=Open%20in&message=Che%20dogfooding%20server%20&logo=eclipseche&color=FDB940&labelColor=525C86)](https://che-dogfooding.apps.che-dev.x6e0.p1.openshiftapps.com/f?url=https://github.com/azatsarynnyy/web-nodejs-sample/tree/idea-server&policies.create=peruser) [![Dev](https://img.shields.io/static/v1?label=Open%20in&message=DevSandbox%20(hosted%20by%20Red%20Hat)&logo=eclipseche&color=FDB940&labelColor=525C86)](https://devspaces.apps.sandbox-m4.g2pi.p1.openshiftapps.com/f?url=https://github.com/azatsarynnyy/web-nodejs-sample/tree/idea-server&policies.create=peruser)

## Built images

The image built from the `main` branch is published to [quay.io/che-incubator/che-idea-dev-server:next](https://quay.io/repository/che-incubator/che-idea-dev-server?tab=tags).

## Image build

`podman build . -f build/dockerfiles/Dockerfile -t che-idea-dev-server:next`

## License

- [Eclipse Public License 2.0](LICENSE)

## Trademark

"Che" is a trademark of the Eclipse Foundation.
