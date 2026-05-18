$file = 'e:\proj\Opedrgent\app\src\main\java\top\hsyscn\opedrgent\ui\AppRoot.kt'
$lines = Get-Content $file -Encoding UTF8

# Find line with 'when (tab) {' and the next line that closes it
$total = $lines.Count
for ($i = 0; $i -lt $total; $i++) {
    if ($lines[$i] -match 'when \(tab\) \{') {
        Write-Host "Found 'when (tab) {' at line $i"
        # Find the closing }
        $openCount = 1
        for ($j = $i+1; $j -lt $total; $j++) {
            if ($lines[$j] -match '^\s*\}') { $openCount-- }
            if ($openCount -eq 0) {
                Write-Host "Closing } at line $j"
                # Remove lines i through j
                $newLines = $lines[0..($i-1)] + $lines[($j+1)..($total-1)]
                $newLines | Set-Content $file -Encoding UTF8
                Write-Host "Done. New file has $($newLines.Count) lines"
                exit
            }
        }
    }
}
