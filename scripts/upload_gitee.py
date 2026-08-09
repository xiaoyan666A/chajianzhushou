#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
上传 APK 到 Gitee Release 附件并输出下载直链（供 GitHub Actions 发版流程使用）。
Gitee 为国内代码托管平台，Release 附件国内可直接下载（免登录），作为 App 更新的国内下载源。

用法:
  python3 upload_gitee.py --apk app.apk --tag v3.48 \
      --owner xxx --repo chajianzhushou --token yyy

凭据也可用环境变量 GITEE_OWNER / GITEE_REPO / GITEE_TOKEN 提供。
成功时 stdout 输出一行: DIRECT_URL=<下载直链>

接口（Gitee OpenAPI v5）：
  1. GET  /repos/{owner}/{repo}/releases/tags/{tag}  查 Release，不存在则创建
  2. POST /repos/{owner}/{repo}/releases             创建 Release
  3. GET  /repos/{owner}/{repo}/releases/{id}/attach_files  查已上传附件
  4. DELETE /repos/{owner}/{repo}/releases/{id}/attach_files/{asset_id} 删除同名旧附件
  5. POST /repos/{owner}/{repo}/releases/{id}/attach_files  上传附件（multipart）
"""

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid

API = "https://gitee.com/api/v5"


class ApiError(Exception):
    pass


def http_request(method, url, fields=None, multipart=None, timeout=120):
    """fields=表单字段（application/x-www-form-urlencoded）；multipart=(file_field, filename, file_bytes)"""
    data = None
    headers = {"User-Agent": "chajianzhushou-release"}
    if multipart:
        boundary = "----giteeboundary" + uuid.uuid4().hex
        parts = []
        for k, v in (fields or {}).items():
            parts.append(
                ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                 % (boundary, k, v)).encode("utf-8")
            )
        file_field, filename, file_bytes = multipart
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
             "Content-Type: application/octet-stream\r\n\r\n"
             % (boundary, file_field, filename)).encode("utf-8")
        )
        parts.append(file_bytes)
        parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
        data = b"".join(parts)
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
    elif fields:
        data = urllib.parse.urlencode(fields).encode("utf-8")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"message": raw[:300]}


def require(resp, action, ok_codes=(200, 201, 204)):
    code, body = resp
    if code not in ok_codes:
        msg = body.get("message") if isinstance(body, dict) else body
        raise ApiError("%s失败(HTTP %s): %s" % (action, code, msg))
    return body


def get_release(token, owner, repo, tag):
    url = "%s/repos/%s/%s/releases/tags/%s?access_token=%s" % (
        API, owner, repo, urllib.parse.quote(tag), urllib.parse.quote(token))
    code, body = http_request("GET", url)
    if code == 200 and isinstance(body, dict) and body.get("id"):
        return body["id"]
    return None


def create_release(token, owner, repo, tag, name):
    url = "%s/repos/%s/%s/releases" % (API, owner, repo)
    fields = {
        "access_token": token,
        "tag_name": tag,
        "name": name,
        "body": "自动同步 GitHub Release（GitHub Actions）",
        "target_commitish": "master",
    }
    body = require(http_request("POST", url, fields=fields), "创建 Release")
    return body["id"]


def list_assets(token, owner, repo, release_id):
    url = "%s/repos/%s/%s/releases/%s/attach_files?access_token=%s&per_page=100" % (
        API, owner, repo, release_id, urllib.parse.quote(token))
    code, body = http_request("GET", url)
    if code != 200:
        return []
    return body if isinstance(body, list) else []


def delete_asset(token, owner, repo, release_id, asset_id):
    url = "%s/repos/%s/%s/releases/%s/attach_files/%s?access_token=%s" % (
        API, owner, repo, release_id, asset_id, urllib.parse.quote(token))
    require(http_request("DELETE", url), "删除旧附件")


def upload_asset(token, owner, repo, release_id, filename, apk_path):
    url = "%s/repos/%s/%s/releases/%s/attach_files" % (API, owner, repo, release_id)
    with open(apk_path, "rb") as f:
        file_bytes = f.read()
    body = require(
        http_request(
            "POST",
            url,
            fields={"access_token": token},
            multipart=("file", filename, file_bytes),
            timeout=300,
        ),
        "上传附件",
    )
    download_url = body.get("browser_download_url") if isinstance(body, dict) else None
    if not download_url:
        raise ApiError("上传附件失败：响应无 browser_download_url")
    return download_url


def main():
    ap = argparse.ArgumentParser(description="上传 APK 到 Gitee Release 并输出下载直链")
    ap.add_argument("--apk", required=True, help="APK 文件路径")
    ap.add_argument("--tag", required=True, help="Release 标签（如 v3.48）")
    ap.add_argument("--owner", default=os.environ.get("GITEE_OWNER"), help="Gitee 用户名/组织")
    ap.add_argument("--repo", default=os.environ.get("GITEE_REPO"), help="Gitee 仓库名")
    ap.add_argument("--token", default=os.environ.get("GITEE_TOKEN"), help="Gitee 私人令牌")
    args = ap.parse_args()

    if not args.owner or not args.repo or not args.token:
        sys.stderr.write("缺少 GITEE_OWNER / GITEE_REPO / GITEE_TOKEN\n")
        return 2
    if not os.path.isfile(args.apk):
        sys.stderr.write("APK 不存在: %s\n" % args.apk)
        return 2

    filename = os.path.basename(args.apk)
    release_id = get_release(args.token, args.owner, args.repo, args.tag)
    if release_id is None:
        release_id = create_release(args.token, args.owner, args.repo, args.tag, args.tag)
    # 同名附件先删除再上传，保证同版本重发时下载链接对应最新文件
    for asset in list_assets(args.token, args.owner, args.repo, release_id):
        if asset.get("name") == filename and asset.get("id"):
            delete_asset(args.token, args.owner, args.repo, release_id, asset["id"])
    direct = upload_asset(args.token, args.owner, args.repo, release_id, filename, args.apk)
    print("DIRECT_URL=%s" % direct)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except ApiError as e:
        sys.stderr.write("错误: %s\n" % e)
        sys.exit(1)
    except Exception as e:
        sys.stderr.write("错误: %s\n" % e)
        sys.exit(1)
