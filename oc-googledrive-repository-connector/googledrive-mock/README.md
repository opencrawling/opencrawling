# Google Drive API v3 Local Mock Server

Stdlib Python 3 emulator for the Google Drive REST API v3 and Google OAuth2 token endpoint, used by `scripts/test-googledrive-connector.sh` and local decoupled testing without contacting live Google Cloud APIs.

## Running locally

```bash
GOOGLEDRIVE_PORT=18490 python3 oc-googledrive-repository-connector/googledrive-mock/googledrive_mock.py
```
