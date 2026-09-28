#!/usr/bin/env bash
# Phase 2 / P2.S2.2 verification against the real bucket. Run from Git Bash with the
# "AwsDocumentApiApplication (S3)" run configuration started in IntelliJ (port 8080).
#
#   bash scripts/s3-smoke.sh            # checks 1-7 and 9
#
# Each check prints EXPECT before it runs so you can compare. Nothing here needs AWS
# credentials except the two `aws s3api` listings; the API does the signing.
set -u
export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2:/c/Users/sbarn/AppData/Local/Microsoft/WinGet/Packages/jqlang.jq_Microsoft.Winget.Source_8wekyb3d8bbwe"
export AWS_PROFILE="${AWS_PROFILE:-document-api}" AWS_DEFAULT_REGION=us-east-1
API=http://localhost:8080; BUCKET=docapi-documents-7fb3fd47; OWNER="X-User-Id: u1"
echo hello > hello.txt

step() { printf '\n== %s\n   EXPECT: %s\n' "$1" "$2"; }

step "0 create" "201; uploadUrl host is the bucket, path /documents/<uuid>-hello.txt, X-Amz-Expires=900, SignedHeaders=content-type;host"
curl -s -X POST "$API/documents" -H "$OWNER" -H "Content-Type: application/json" \
     -d '{"filename":"hello.txt","contentType":"text/plain"}' > created.json
ID=$(jq -r .document.id created.json); URL=$(jq -r .uploadUrl created.json)
echo "   id=$ID"; echo "   url=$URL" | sed -E 's/(X-Amz-Signature=)[0-9a-f]+/\1.../'

step "2 PUT the bytes straight to S3" "200"
curl -s -o /dev/null -w "   HTTP %{http_code}\n" -X PUT -H "Content-Type: text/plain" --upload-file hello.txt "$URL"

step "3 object is in the bucket under the prefix" "documents/<uuid>-hello.txt listed"
aws s3api list-objects-v2 --bucket "$BUCKET" --prefix documents/ --query 'Contents[].Key' --output text | tr '\t' '\n' | sed 's/^/   /'

step "4 encrypted with the key, versioned" "aws:kms, key 07a7a487…, a VersionId"
aws s3api head-object --bucket "$BUCKET" --key "documents/$ID-hello.txt" \
    --query '{SSE:ServerSideEncryption,Key:SSEKMSKeyId,VersionId:VersionId,Size:ContentLength}' --output text | sed 's/^/   /'

step "5 THE GAP" "\"PENDING_UPLOAD\" and null - bytes are in S3, the API does not know"
curl -s "$API/documents/$ID" -H "$OWNER" | jq -c '[.document.status, .downloadUrl]' | sed 's/^/   /'

step "6 wrong Content-Type on the same URL" "403, body mentions SignatureDoesNotMatch"
curl -s -X PUT -H "Content-Type: image/png" --upload-file hello.txt "$URL" -w "   HTTP %{http_code}\n" | grep -oE "<Code>[A-Za-z]+</Code>|HTTP [0-9]+" | sed 's/^/   /'

# Two independent gates refuse plain HTTP here. For a PUT that will be SSE-KMS encrypted, S3's own
# rule ("requests specifying SSE with KMS keys must be made over a secure connection") is checked
# during request validation, before authorization, so it answers first with 400 InvalidArgument.
# The bucket policy's explicit deny (403 AccessDenied) is what a plain-HTTP GET hits, or a PUT with
# SSE-S3. Both refuse; the earlier check wins.
step "7 plain HTTP with a valid signature" "400 InvalidArgument (S3's SSE-KMS-requires-TLS rule fires before the bucket policy's 403)"
curl -s -X PUT -H "Content-Type: text/plain" --upload-file hello.txt "${URL/https:/http:}" -w "   HTTP %{http_code}\n" | grep -oE "<Code>[A-Za-z]+</Code>|HTTP [0-9]+" | sed 's/^/   /'

step "9 DELETE through the API (exercises your delete())" "204, then the key is gone from the listing but a DeleteMarker exists"
curl -s -o /dev/null -w "   HTTP %{http_code}\n" -X DELETE "$API/documents/$ID" -H "$OWNER"
echo "   current objects:"; aws s3api list-objects-v2 --bucket "$BUCKET" --prefix documents/ --query 'Contents[].Key' --output text | tr '\t' '\n' | sed 's/^/     /'
echo "   delete markers:"; aws s3api list-object-versions --bucket "$BUCKET" --prefix "documents/$ID" --query 'DeleteMarkers[].Key' --output text | sed 's/^/     /'

printf '\nCheck 8 (expired URL) is manual: stop the app, add DOCAPI_PRESIGN_TTL=PT10S to the (S3) run config,\nstart, re-run steps 0 and 2 after 15 s -> 403 "Request has expired". Then remove the variable.\n'
rm -f hello.txt created.json
