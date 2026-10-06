$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$classes = Join-Path $PSScriptRoot 'build/language-check'
New-Item -ItemType Directory -Force $classes | Out-Null
& javac -encoding UTF-8 -d $classes "$PSScriptRoot/src/sts1solver/I18n.java" "$PSScriptRoot/test/LanguageCheck.java"
if ($LASTEXITCODE -ne 0) { throw 'Language check compilation failed' }
& java '-Dfile.encoding=UTF-8' -cp "$classes;$PSScriptRoot/resources" sts1solver.LanguageCheck
if ($LASTEXITCODE -ne 0) { throw 'Language check failed' }
