// Development only: the page's own origin stands in for the Benbow server, as it does when the server serves /app/
// (ADR-029). /api goes on to a dev server (never the owner's), whose self-signed certificate node accepts.
if (config.devServer) {
    config.devServer.proxy = [{
        context: ['/api'],
        target: process.env.BENBOW_DEV_SERVER || 'https://127.0.0.1:8797',
        secure: false,
        changeOrigin: true,
    }];
}
