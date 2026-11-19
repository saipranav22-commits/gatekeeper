import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

describe('Nginx Reverse Proxy Security Configuration', () => {
  const nginxConfPath = path.resolve(__dirname, '../../nginx.conf');
  const nginxConf = fs.readFileSync(nginxConfPath, 'utf-8');

  it('strictly excludes broad RFC 1918 private CIDRs from trusted edge proxies', () => {
    // Broad private networks must not be implicitly trusted
    expect(nginxConf).not.toMatch(/10\.0\.0\.0\/8\s+1;/);
    expect(nginxConf).not.toMatch(/172\.16\.0\.0\/12\s+1;/);
    expect(nginxConf).not.toMatch(/192\.168\.0\.0\/16\s+1;/);
  });

  it('declares explicit loopback proxies with default deny for untrusted connections', () => {
    expect(nginxConf).toMatch(/geo\s+\$remote_addr\s+\$trusted_edge_proxy\s*\{/);
    expect(nginxConf).toMatch(/default\s+0;/);
    expect(nginxConf).toMatch(/127\.0\.0\.1\s+1;/);
    expect(nginxConf).toMatch(/::1\s+1;/);
  });

  it('implements protocol sanitization protecting X-Forwarded-Proto', () => {
    expect(nginxConf).toMatch(/map\s+\$http_x_forwarded_proto\s+\$edge_proto/);
    expect(nginxConf).toMatch(/map\s+\$trusted_edge_proxy\s+\$forwarded_proto\s*\{\s*1\s+\$edge_proto;\s*default\s+\$scheme;/s);
  });

  it('implements host header sanitization protecting X-Forwarded-Host', () => {
    expect(nginxConf).toMatch(/map\s+\$http_x_forwarded_host\s+\$edge_host/);
    expect(nginxConf).toMatch(/map\s+\$trusted_edge_proxy\s+\$forwarded_host\s*\{\s*1\s+\$edge_host;\s*default\s+\$host;/s);
  });

  it('implements client IP sanitization protecting X-Forwarded-For from untrusted spoofing', () => {
    expect(nginxConf).toMatch(/map\s+\$trusted_edge_proxy\s+\$forwarded_for\s*\{\s*1\s+\$proxy_add_x_forwarded_for;\s*default\s+\$remote_addr;/s);
  });

  it('forwards sanitized headers to upstream gateway location /api/', () => {
    expect(nginxConf).toMatch(/proxy_set_header\s+X-Forwarded-Proto\s+\$forwarded_proto;/);
    expect(nginxConf).toMatch(/proxy_set_header\s+X-Forwarded-Host\s+\$forwarded_host;/);
    expect(nginxConf).toMatch(/proxy_set_header\s+X-Forwarded-For\s+\$forwarded_for;/);
    expect(nginxConf).toMatch(/proxy_set_header\s+Host\s+\$host;/);
    expect(nginxConf).toMatch(/proxy_set_header\s+X-Real-IP\s+\$remote_addr;/);
  });
});
