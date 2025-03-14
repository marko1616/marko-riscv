// -*- mode: C++; c-file-style: "cc-mode" -*-
//*************************************************************************
//
// Copyright 2003-2012 by Wilson Snyder. This program is free software; you can
// redistribute it and/or modify it under the terms of either the GNU
// Lesser General Public License Version 3 or the Perl Artistic License.
// Version 2.0.
//
// Verilator is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
//*************************************************************************
///
/// \file
/// \brief Verilator: Auto version information include for all Verilated C files
///
/// Code available from: http://www.veripool.org/verilator
///
//*************************************************************************


///**** Product and Version name

// Autoconf substitutes this with the strings from AC_INIT.
#define VERILATOR_PRODUCT    "@PACKAGE_NAME@"
#define VERILATOR_VERSION    "@PACKAGE_VERSION@"