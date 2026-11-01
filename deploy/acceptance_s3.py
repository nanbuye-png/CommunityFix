"""Small SigV4 client for the isolated local MinIO acceptance bucket only."""
from datetime import datetime, timezone
import hashlib
import hmac
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


class S3:
    def __init__(self, endpoint, access, secret, bucket='acceptance'):
        parsed = urllib.parse.urlsplit(endpoint)
        if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or parsed.path:
            raise ValueError('Acceptance S3 endpoint must be loopback HTTP')
        self.endpoint, self.host = endpoint, parsed.netloc
        self.access, self.secret, self.bucket = access, secret, bucket

    def request(self, method, key=None, data=b'', query=None, content_type=None):
        path = '/' + self.bucket + ('/' + urllib.parse.quote(key, safe='/~') if key is not None else '')
        query = '&'.join(urllib.parse.quote(k, safe='~') + '=' + urllib.parse.quote(v, safe='~')
                         for k, v in sorted((query or {}).items()))
        now = datetime.now(timezone.utc)
        stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
        digest = hashlib.sha256(data).hexdigest()
        signed = 'host;x-amz-content-sha256;x-amz-date'
        headers = f'host:{self.host}\nx-amz-content-sha256:{digest}\nx-amz-date:{stamp}\n'
        canonical = '\n'.join([method, path, query, headers, signed, digest])
        scope = day + '/us-east-1/s3/aws4_request'
        message = '\n'.join(['AWS4-HMAC-SHA256', stamp, scope, hashlib.sha256(canonical.encode()).hexdigest()])
        signing = ('AWS4' + self.secret).encode()
        for part in (day, 'us-east-1', 's3', 'aws4_request'):
            signing = hmac.new(signing, part.encode(), hashlib.sha256).digest()
        signature = hmac.new(signing, message.encode(), hashlib.sha256).hexdigest()
        request_headers = {'Host': self.host, 'x-amz-date': stamp, 'x-amz-content-sha256': digest,
            'Authorization': f'AWS4-HMAC-SHA256 Credential={self.access}/{scope}, SignedHeaders={signed}, Signature={signature}'}
        if content_type:
            request_headers['Content-Type'] = content_type
        req = urllib.request.Request(self.endpoint + path + ('?' + query if query else ''),
            data=data if method in ('PUT', 'POST') else None, headers=request_headers, method=method)
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.read(), dict(response.headers)

    def inventory(self):
        result, token = {}, None
        while True:
            query = {'list-type': '2'}
            if token:
                query['continuation-token'] = token
            root = ET.fromstring(self.request('GET', query=query)[0])
            for item in root.findall('{*}Contents'):
                key = item.findtext('{*}Key')
                data, headers = self.request('GET', key)
                result[key] = dict(size=len(data), sha256=hashlib.sha256(data).hexdigest(),
                                   contentType=headers.get('Content-Type', 'application/octet-stream'))
            if root.findtext('{*}IsTruncated') != 'true':
                return result
            token = root.findtext('{*}NextContinuationToken')
            if not token:
                raise RuntimeError('Truncated object listing has no continuation token')
