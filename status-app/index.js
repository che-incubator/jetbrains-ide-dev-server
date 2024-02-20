/**
 * Copyright (c) 2023 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */

const chokidar = require('chokidar');
const fs = require("fs");
const express = require('express');

// path to the IDEA server's output
const logsFile = '/idea-server/std.out';

// watch for the 'Join-link' in the IDEA server's output
var joinLink = new Promise((resolve) => {
  const watcher = chokidar.watch(logsFile);
  watcher.on('change', (event, path) => {
    fs.readFile(logsFile, "utf-8", (err, data) => {
      if (err)
        throw err;

      if (data.includes('Join link: tcp://')) {
        const tcpLinkRegex = /(tcp:\/\/[^\s]+)/g;
        data.replace(tcpLinkRegex, function (link) {
          console.log('TCP join link is accuired: ' + link);
          resolve(link);
          watcher.close().then(() => console.log('Watcher is closed!'));
        });
      }
    });
  });
});

// return the status page
const app = express();
app.set('view engine', 'ejs');
app.get('/', async function (req, res) {
  const invitationLink = (await joinLink).replaceAll('&', '_');
  const dwNamespace = process.env.DEVWORKSPACE_NAMESPACE;
  const dwName = process.env.DEVWORKSPACE_NAME;
  const clusterConsoleURL = process.env.CLUSTER_CONSOLE_URL;
  const podName = process.env.HOSTNAME;
  // render the page from EJS template
  res.render('status', { dwNamespace, dwName, clusterConsoleURL, podName, invitationLink });
});

// server setup
const appPort = '3400';
app.listen(appPort, function (err) {
  if (err)
    throw err;

  console.log('Status app is listening on port', appPort);
});
