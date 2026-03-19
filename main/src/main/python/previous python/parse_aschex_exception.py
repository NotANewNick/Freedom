################################################################
# Copyright (C) 2017 Queclink Wirless Solutions Co., Ltd.
# All Rights Reserved
#
# This code is provided by Queclink to its customers as a 
# sample to demonstrate how to how to parse HEX format 
# @Track protocol messages.
#
# Permission to use, copy and modify this code for the purpose
# of use or test Queclink devices is hereby granted, provided 
# that the above copyright notice, this paragraph and the 
# following paragraph appear in all copies, modifications.
#
# You must not distribute this code to others without express 
# authority from Queclink.
################################################################

class ParseError(Exception):
    """Base class for parser exception"""
    pass

class ParseLengthError(ParseError):
    pass

class ParseAscHeaderError(ParseError):
    """Unkonwn header, treat as ASCII protocol message"""
    pass

class ParseFormatError(ParseError):
    pass

class ParseTailError(ParseError):
    pass

class ParseNotImp(ParseError):
    pass
