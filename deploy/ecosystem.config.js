// pm2 config — deploy/setup_server.sh rewrites the port (8010) to whatever
// free port it found before running `pm2 start deploy/ecosystem.config.js`.
module.exports = {
  apps: [
    {
      name: "ytdownloader",
      script: "venv/bin/uvicorn",
      args: "app:app --host 127.0.0.1 --port 8010 --workers 1",
      interpreter: "none",
      cwd: __dirname + "/..",
      autorestart: true,
      // --workers 1 is required: playlist/download progress lives in
      // process memory, so more workers would break progress streaming.
    },
  ],
};
