/**
 * Copyright (c) 2023-2025 Red Hat, Inc.
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
const path = require('path');
const express = require('express');
const ideInfo = require('../product-info.json');

// path to the IDE server's logs
const logsFile = '../std.out';

// watch for the 'joinLink' in the IDE server's output
var joinLink = new Promise((resolve, reject) => {
  const watcher = chokidar.watch(logsFile);

  watcher.on('change', (event, path) => {
    fs.readFile(logsFile, "utf-8", (err, data) => {
      if (err)
        reject(err);

      if (data.includes('Join link: tcp://')) {
        const tcpLinkRegex = /(tcp:\/\/[^\s]+)/g;
        data.replace(tcpLinkRegex, function (link) {
          console.log('TCP join link is acquired: ' + link);
          resolve(link);
          watcher.close().then(() => console.log('Watcher is closed!'));
        });
      }
    });
  });
});

// return the status page
const app = express();
app.use(express.static(path.join(__dirname, 'public')));
app.set('view engine', 'ejs');
app.get('/', async function (req, res) {
  const ideFullName = ideInfo.productVendor + ' ' + ideInfo.name + ' ' + ideInfo.version;

  var invitationLink;
  try {
    // Some user's containers contain Node.js<15
    // which doesn't support replaceAll('&', '_')
    // So, use replace with regexp here.
    invitationLink = (await joinLink).replace(/['&']/g, '_');
  } catch (err) {
    console.error('Error while getting a join link', err);
  }

  const dwNamespace = process.env.DEVWORKSPACE_NAMESPACE;
  const dwName = process.env.DEVWORKSPACE_NAME;
  const title = dwName;
  const clusterConsoleURL = process.env.CLUSTER_CONSOLE_URL;
  const dashboardURL = process.env.CHE_DASHBOARD_URL;

  // derive OAuth URL from console URL
  let oauthURL;
  if (clusterConsoleURL) {
    try {
      const url = new URL(clusterConsoleURL);
      url.hostname = url.hostname.replace('console-openshift-console', 'oauth-openshift');
      oauthURL = `${url.protocol}//${url.hostname}/oauth/token/request`;
    } catch (e) {
      console.error('Invalid clusterConsoleURL', e);
    }
  }

  // render the page from EJS template
  res.render('status', { title, ideFullName, dwNamespace, dwName, oauthURL, invitationLink, dashboardURL });
});

// server setup
const appPort = '3400';
app.listen(appPort, function (err) {
  if (err)
    throw err;

  console.log('Status app is listening on port', appPort);
});
