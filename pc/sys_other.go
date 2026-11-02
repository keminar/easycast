//go:build !windows

package main

import "errors"

func openBrowser(string) {}

func pickFile() (string, error) { return "", errors.New("只支持 Windows") }

func allowFirewall(int) error { return errors.New("只支持 Windows") }
