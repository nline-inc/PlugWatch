#! /usr/bin/env python3

import os
import sys
import requests
import argparse
import json

parser = argparse.ArgumentParser(description = 'Deploy particle firmware')
parser.add_argument('-f','--fname', type=str, required=True,action='append')
parser.add_argument('-t','--title', type=str, required=True)
parser.add_argument('-d','--filter',type=str,required=False)

args = parser.parse_args()

#get the key from the particle file/login
key_file = open(os.environ['HOME'] + '/.particle/particle.config.json','r')
particle_key = None
try:
    keys = json.loads(key_file.read())
    particle_key = keys['access_token']
except:
    print("Install particle CLI and login with 'particle login' to generate a key file. Exiting.")
    sys.exit(1)

#check the file name to see if it is a file or folder
file_list = []
for f in args.fname:
    if os.path.isfile(f):
        file_list.append(f)
    elif os.path.isdir(f):
        file_list = [f + '/' + f for f in os.listdir(f)]
    else:
        print("Fname is neither a file nor directory - exiting")
        sys.exit(1)

print()
for f in file_list:
    print("Flashing {}".format(f))

    #extract the product id and version numbers
    binary = open(f, 'rb')
    data = binary.read()

    #the product version
    version_bytes = [data[-41], data[-42]]
    product_id_bytes = [data[-43], data[-44]]
    version_string = str(int(''.join('{:02X}'.format(x) for x in version_bytes),16))
    product_string = str(int(''.join('{:02X}'.format(x) for x in product_id_bytes),16))

    #get the devices filter if it exists
    device_filter_list = [];
    if args.filter != None:
        with open(args.filter) as f:
            device_filter_list = f.readlines()
    device_filter_list = [x.strip() for x in device_filter_list]

    print()
    print('Uploading firmware to the particle cloud...')
    #first upload the binary file to the cloud
    r = requests.post("https://api.particle.io/v1/products/" + product_string + "/firmware?access_token=" + particle_key,
            data = {'version': version_string, 'title':args.title}, files=dict(binary=open(f, 'rb')))

    resp = json.loads(r.text)
    if 'ok' in resp:
        if(resp['ok'] is False):
            print('Firmware upload failed: ' + resp['error'])
    else:
        print('Firmware upload succeeded')

    print()

    #now get a list of devices in the product
    print('Getting list of devices in product...')
    r = requests.get("https://api.particle.io/v1/products/" + product_string + "/devices?access_token=" + particle_key + '&perPage=1000&sortAttr=firmwareVersion&sortDir=desc')

    resp = json.loads(r.text)
    if 'ok' in resp:
        if(resp['ok'] is False):
            print('Getting devices failed: ' + resp['error'])
    else:
        print('Getting devices succeeded')

    devices = json.loads(r.text)
    print()



    #now iterate through the product locking each device ID to the new version
    print('Locking devices to new firmware...')
    for device in devices['devices']:
        if(device['id'] not in device_filter_list and args.filter != None):
            print('Skipping {} - not in filter list'.format(device['id']));
            continue

        if('firmware_version' in device and device['firmware_version'] > int(version_string)):
            print('Skipping {} - already has greater version'.format(device['id']));
            continue


        r = requests.put("https://api.particle.io/v1/products/" + product_string + "/devices/" + device['id'],
            data = {'desired_firmware_version': version_string, 'access_token':particle_key, 'flash':'true'})
        resp = json.loads(r.text)
        if 'ok' in resp:
            if(resp['ok'] is False):
                if 'error' in resp:
                    print('Locking device ' + device['id'] + ' failed: ' + resp['error'])
                elif 'errors' in resp:
                    print('Locking device ' + device['id'] + ' failed: ' + resp['errors'][0])
        else:
            print('Locking device ' + device['id'] + ' succeeded')

    print()

    #now release the version to the product
    print('Releasing firmware...')
    r = requests.put("https://api.particle.io/v1/products/" + product_string + "/firmware/release",
            data = {'version': version_string, 'access_token':particle_key, 'product_default':'true'})
    resp = json.loads(r.text)
    if 'ok' in resp:
        if(resp['ok'] is False):
            print('Releasing firmware failed: ' + resp['error'])
    else:
        print('Releasing firmware succeeded')

