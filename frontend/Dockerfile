# Runtime image for the web app. Build on the host first (uses your local node_modules):
#   cd C:\dev\iccp\frontend; npx ng build
# Dockerfile.ci builds inside Docker instead.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
