//go:build windows

package main

import (
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// pastasDocumentos começa pela pasta Documentos que o próprio Windows
// informa (a mesma que o EU4 usa, inclusive quando o OneDrive a move) e
// depois tenta os lugares comuns.
func pastasDocumentos() []string {
	var pastas []string
	if p, err := windows.KnownFolderPath(windows.FOLDERID_Documents, 0); err == nil {
		pastas = append(pastas, p)
	}
	if casa, err := os.UserHomeDir(); err == nil {
		pastas = append(pastas,
			filepath.Join(casa, "Documents"),
			filepath.Join(casa, "OneDrive", "Documentos"),
			filepath.Join(casa, "OneDrive", "Documents"))
	}
	if od := os.Getenv("OneDrive"); od != "" {
		pastas = append(pastas, filepath.Join(od, "Documentos"), filepath.Join(od, "Documents"))
	}
	return pastas
}

// pastasSteam lê do registro onde o Steam está instalado.
func pastasSteam() []string {
	var pastas []string
	if k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Valve\Steam`, registry.QUERY_VALUE); err == nil {
		if v, _, err := k.GetStringValue("SteamPath"); err == nil {
			pastas = append(pastas, filepath.FromSlash(v))
		}
		k.Close()
	}
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\WOW6432Node\Valve\Steam`, registry.QUERY_VALUE); err == nil {
		if v, _, err := k.GetStringValue("InstallPath"); err == nil {
			pastas = append(pastas, v)
		}
		k.Close()
	}
	return append(pastas, `C:\Program Files (x86)\Steam`, `C:\Program Files\Steam`)
}

func pastasFixasDoJogo() []string {
	return []string{
		`C:\GOG Games\Europa Universalis IV`,
		`C:\Program Files (x86)\GOG Galaxy\Games\Europa Universalis IV`,
	}
}
