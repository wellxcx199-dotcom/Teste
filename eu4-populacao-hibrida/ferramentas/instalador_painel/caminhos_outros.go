//go:build !windows

package main

import (
	"os"
	"path/filepath"
)

// Versão para Linux e macOS: serve para testar o instalador fora do
// Windows e para quem joga o EU4 nesses sistemas.

func pastasDocumentos() []string {
	casa, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	return []string{
		filepath.Join(casa, ".local", "share"),
		filepath.Join(casa, "Documents"),
	}
}

func pastasSteam() []string {
	casa, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	return []string{
		filepath.Join(casa, ".steam", "steam"),
		filepath.Join(casa, ".local", "share", "Steam"),
		filepath.Join(casa, "Library", "Application Support", "Steam"),
	}
}

func pastasFixasDoJogo() []string { return nil }
