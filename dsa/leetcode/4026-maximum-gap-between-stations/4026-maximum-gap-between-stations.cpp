class Solution {
public:
    int maximumGap(string skill, string station) {
        int n = size(skill);
        vector<int> early(n);
        vector<int> late(n);
        int j = 0;
        for(int i = 0 ; i < n ;i++){
            while(station[j] != skill[i]) j++;
            early[i] = j;
            j++;
        }
        j = size(station)-1;
        for(int i = n-1 ; i >= 0 ;i--){
            while(station[j] != skill[i]) j--;
            late[i] = j;
            j--;
        }
        int res = 0;
        for(int i = 1 ; i < n ; i++){
            res = max(res,late[i]-early[i-1]);
        }
        return res;
    }
};
