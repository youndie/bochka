{{/*
The port bochka listens on, everywhere in this chart.

Not a value, and this is the one place in the server where a wrong setting is accepted in silence:
a BOCHKA_PORT that does not parse makes `int()` return null, the port falls back to 9000, and the
startup dump prints the string you gave it (Main.kt:32). Kubelet's service links can produce
exactly that — `BOCHKA_PORT=tcp://10.x.x.x:9000` — which is the other half of why
`enableServiceLinks: false` is in the pod spec.
*/}}
{{- define "bochka.port" -}}9000{{- end -}}

{{- define "bochka.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "bochka.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "bochka.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The tag this release actually runs, resolved once.

`.Chart.AppVersion` is what the chart ships with and not what the pod runs: `image.tag` overrides it,
and the harness in this repository does exactly that (`--set image.tag=...` for a locally built
image), as does anybody pinning a fork or an older release. Everything the operator reads the version
from — the first line of NOTES, the `app.kubernetes.io/version` label, `kubectl get pods -L` — has to
come from here, or it names an image that is not in the cluster.
*/}}
{{- define "bochka.imageTag" -}}
{{- .Values.image.tag | default .Chart.AppVersion -}}
{{- end -}}

{{/*
Selector labels: the two that identify this instance, and nothing else.

A StatefulSet's selector cannot be changed after it is created, so anything that might ever move —
a version, a chart revision — has to stay out of it.
*/}}
{{- define "bochka.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bochka.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "bochka.labels" -}}
helm.sh/chart: {{ include "bochka.chart" . }}
{{ include "bochka.selectorLabels" . }}
{{- with include "bochka.imageTag" . }}
{{- /*
The tag, not the appVersion: this label is what `kubectl get pods -L app.kubernetes.io/version`
prints, and a pinned image made it print a version nobody was running. Truncated because a tag may
be up to 128 characters and a label value may be 63.
*/}}
app.kubernetes.io/version: {{ . | trunc 63 | trimSuffix "-" | quote }}
{{- end }}
app.kubernetes.io/component: storage
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
Where BOCHKA_KEYS comes from: an existing Secret, or the one this chart renders.
*/}}
{{- define "bochka.secretName" -}}
{{- if .Values.auth.existingSecret -}}
{{- .Values.auth.existingSecret -}}
{{- else -}}
{{- include "bochka.fullname" . -}}
{{- end -}}
{{- end -}}

{{- define "bochka.secretKey" -}}
{{- .Values.auth.existingSecretKey | default "keys" -}}
{{- end -}}

{{/*
`auth.keys` joined into the one string the server parses: `id:secret,id2:secret2`.

The shape is not ours to choose — pairs split on ',', each pair split on its FIRST ':'
(Configuration.kt:73-74, Main.kt:44-46). Everything refused below is refused because the server
would accept it and mean something else:

  a comma in a secret      cuts the pair in half and turns the tail into another id:secret;
  a colon in an id         moves the boundary, so the id is the part before it and the rest of
                           the id joins the secret;
  a blank secret           is `IllegalArgumentException: a secret cannot be blank`, exit 1;
  surrounding whitespace   is trimmed by the server, so the client signs with a different string
                           than the one in the values file and sees SignatureDoesNotMatch;
  a repeated id            is `associate`, which keeps the last silently and drops the earlier one.
*/}}
{{- define "bochka.keysValue" -}}
{{- $pairs := list -}}
{{- $seen := dict -}}
{{- range $entry := .Values.auth.keys -}}
{{- $id := $entry.id | default "" | toString -}}
{{- $secret := $entry.secret | default "" | toString -}}
{{- if eq $id "" -}}
{{- fail "auth.keys: an entry has an empty id, and `id:secret` with nothing before the colon is `keys look like id:secret`, exit 1" -}}
{{- end -}}
{{- if ne $id (trim $id) -}}
{{- fail (printf "auth.keys: the id %q has leading or trailing whitespace, which the server trims — the id it ends up with is not the one written here" $id) -}}
{{- end -}}
{{- if or (contains ":" $id) (contains "," $id) -}}
{{- fail (printf "auth.keys: the id %q contains ':' or ',', which are the separators BOCHKA_KEYS is built from; pick an id without them" $id) -}}
{{- end -}}
{{- if eq $secret "" -}}
{{- fail (printf "auth.keys: the secret for id %q is empty, and the server refuses to start on that ('a secret cannot be blank', exit 1)" $id) -}}
{{- end -}}
{{- if ne $secret (trim $secret) -}}
{{- fail (printf "auth.keys: the secret for id %q has leading or trailing whitespace, which the server trims — the client would be signing with something else" $id) -}}
{{- end -}}
{{- if or (contains "," $secret) (contains "\n" $secret) -}}
{{- fail (printf "auth.keys: the secret for id %q contains a comma or a newline, either of which splits BOCHKA_KEYS into pairs the server never meant to see" $id) -}}
{{- end -}}
{{- if hasKey $seen $id -}}
{{- fail (printf "auth.keys: the id %q appears more than once; the server keeps the last one silently, so the earlier secrets would simply stop working" $id) -}}
{{- end -}}
{{- $seen = set $seen $id true -}}
{{- $pairs = append $pairs (printf "%s:%s" $id $secret) -}}
{{- end -}}
{{- join "," $pairs -}}
{{- end -}}

{{/*
Everything this chart refuses to render, in one place, so that a bad install fails at `helm
template` rather than in a CrashLoopBackOff whose message reads like an operator error.
*/}}
{{- define "bochka.validate" -}}

{{/*
Keys are required, and there is no safe default to fall back to. An unset or blank BOCHKA_KEYS is
not "no authentication" — it is the two built-in pairs, `bochkaadmin:bochkasecret` and
`bochkaalt:bochkaaltsecret` (Main.kt:48), announced in the log as `keys (unset)` and as nothing
worse. A chart that renders in that state installs a store with published credentials.
*/}}
{{- if and .Values.auth.existingSecret .Values.auth.keys -}}
{{- fail "auth.existingSecret and auth.keys are both set, and they are two answers to one question. Keep the Secret you already have, or let the chart render one — not both." -}}
{{- end -}}
{{- if and (not .Values.auth.existingSecret) (not .Values.auth.keys) -}}
{{- fail "bochka has no access keys. Set auth.keys (--set auth.keys[0].id=...,auth.keys[0].secret=...) or point auth.existingSecret at a Secret that already holds them. There is no default on purpose: an empty BOCHKA_KEYS starts the server with the built-in bochkaadmin/bochkaalt credentials, which are published in this repository, and the log calls that a normal start." -}}
{{- end -}}

{{/*
JVM options and BOCHKA_* are refused. The heap is baked into the start script and the published
object ceiling is derived from it, but the script concatenates `$DEFAULT_JVM_OPTS $JAVA_OPTS
$BOCHKA_APP_OPTS` and HotSpot takes the last -Xmx — measured: `object ceiling: 2395290` against
399215. An unknown BOCHKA_* name stops the process with exit 2 (Configuration.kt:122-127), and a
known one overwrites a setting this chart already renders from values.
*/}}
{{- range $env := .Values.extraEnv -}}
{{- $name := $env.name | toString -}}
{{- if hasPrefix "BOCHKA_" $name -}}
{{- fail (printf "extraEnv: %s is bochka's own namespace. An unknown BOCHKA_* name stops the server with exit 2, and a known one silently overrides what this chart renders. Use the typed fields in values.yaml." $name) -}}
{{- end -}}
{{- if has $name (list "JAVA_OPTS" "JAVA_TOOL_OPTIONS" "_JAVA_OPTIONS" "JDK_JAVA_OPTIONS") -}}
{{- fail (printf "extraEnv: %s replaces the runtime profile the published object ceiling is derived from. The heap is a property of the distribution (-Pbochka.jvmArgs), not of a deployment." $name) -}}
{{- end -}}
{{- end -}}

{{/*
`persistence.existingClaim` with `persistence.enabled: false` is two opposite instructions.
*/}}
{{- if and (not .Values.persistence.enabled) .Values.persistence.existingClaim -}}
{{- fail "persistence.existingClaim is set while persistence.enabled is false, which would mount an emptyDir and ignore the claim." -}}
{{- end -}}

{{/*
A memory limit below the shipped runtime profile is refused, and it is the same refusal as JAVA_OPTS
seen from the other side. That one is rejected because it replaces the heap the published object
ceiling is derived from; a limit under the profile's own footprint destroys the same property from
outside — the OOM kill arrives before the ceiling does, and an OOM kill is a CrashLoopBackOff with no
line saying why, at whatever moment the index happened to grow.

512M heap + 80M metaspace + 32M code cache + 32M direct + stacks is about 700 MiB, and the values
file asks for 768Mi. That is arithmetic from the profile and not a measured kill threshold — the
measured one needs a cluster, a full index and live traffic, and it is a task in BACKLOG.md rather
than a number anybody can quote here — so this guard sits at the number the chart itself recommends
and says where the number comes from.

Quantities are parsed here rather than handed to the API server because the API server has no opinion
about them: 64Mi is a perfectly valid ResourceRequirements. Only the suffixes Kubernetes actually
writes are understood; anything else (an exponent form, a milli-suffix) is left alone rather than
guessed at, because a guard that refuses what it failed to parse is worse than one that admits it.
*/}}
{{- if .Values.resources -}}
{{- if .Values.resources.limits -}}
{{- $raw := .Values.resources.limits.memory | default "" | toString -}}
{{- $digits := regexFind "^[0-9]+" $raw -}}
{{- $unit := trimPrefix $digits $raw -}}
{{- $factor := index (dict "" 1 "Ki" 1024 "Mi" 1048576 "Gi" 1073741824 "Ti" 1099511627776 "k" 1000 "M" 1000000 "G" 1000000000 "T" 1000000000000) $unit | default 0 -}}
{{- if and $digits (gt (int64 $factor) (int64 0)) -}}
{{- $bytes := mul (atoi $digits) $factor -}}
{{- if lt $bytes (int64 805306368) -}}
{{- fail (printf "resources.limits.memory is %s, and the shipped runtime profile needs about 700 MiB before it holds a single object: -Xmx512M plus 80M metaspace, 32M code cache, 32M direct memory and thread stacks, all baked into the start script (build.gradle.kts:26-48). Below 768Mi the pod is OOM-killed while it is working rather than refused at startup, which is exactly the property the object ceiling exists to provide. Raise the limit, or rebuild the distribution with a smaller -Pbochka.jvmArgs heap." $raw) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- end -}}

{{/*
The probe, in whichever of the three shapes was asked for.

`exec` is the only one that proves the server answered. There is no health endpoint and no metrics
in this server — the single unauthenticated answer is `403 AccessDenied` on `GET /`
(S3Handler.kt:71-76, SignatureVerifier.kt:79-80, S3Error.kt:44-53) — and kubelet counts 200-399 as
success for httpGet, so an httpGet probe on `/` is red forever. The image carries no curl, no wget
and no nc; it does carry /usr/bin/bash, and bash has /dev/tcp.
*/}}
{{- define "bochka.probeAction" -}}
{{- if eq .Values.probes.type "exec" -}}
exec:
  command:
    - bash
    - -c
    - 'exec 3<>/dev/tcp/127.0.0.1/{{ include "bochka.port" . }}; printf "GET / HTTP/1.0\r\n\r\n" >&3; head -1 <&3 | grep -q " 403 "'
{{- else if eq .Values.probes.type "tcp" -}}
tcpSocket:
  port: s3
{{- else -}}
{{- fail (printf "probes.type: %q has no probe shape, and a probe block that renders empty is a pod the kubelet cannot judge" .Values.probes.type) -}}
{{- end -}}
{{- end -}}
