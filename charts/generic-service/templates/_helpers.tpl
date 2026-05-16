{{/*
============================================================
通用 Helm 模板辅助函数
============================================================
*/}}

{{/*
通用标签
*/}}
{{- define "generic.labels" -}}
app: {{ .Values.service.name }}
project: {{ .Values.project }}
environment: {{ .Values.environment }}
managed-by: helm
{{- end }}

{{/*
通用 selector 标签
*/}}
{{- define "generic.selectorLabels" -}}
app: {{ .Values.service.name }}
{{- end }}

{{/*
通用 annotations
*/}}
{{- define "generic.annotations" -}}
deploy-tool: helm-deploy
project: {{ .Values.project }}
environment: {{ .Values.environment }}
{{- end }}

{{/*
Prometheus 监控 annotations（Pod 级别）
*/}}
{{- define "generic.monitoringAnnotations" -}}
{{- if .Values.monitoring.enabled }}
prometheus.io/scrape: "true"
prometheus.io/port: "{{ .Values.monitoring.port }}"
prometheus.io/path: "{{ .Values.monitoring.path }}"
{{- end }}
{{- end }}

{{/*
完整镜像地址
*/}}
{{- define "generic.image" -}}
{{ .Values.image.registry }}/{{ .Values.image.name }}:{{ .Values.image.tag }}
{{- end }}

{{/*
工作负载类型判断
*/}}
{{- define "generic.workloadType" -}}
{{ .Values.workloadType | default "deployment" }}
{{- end }}

{{/*
资源名 - service
*/}}
{{- define "generic.serviceName" -}}
{{ .Values.service.name }}-svc
{{- end }}
