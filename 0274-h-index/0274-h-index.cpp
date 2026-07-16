class Solution {
public:
    int hIndex(vector<int>& a) {
        vector<int> count(1001);
        int n = size(a);
        for(int i = 0 ; i < n ; i++){
            count[a[i]]++;
        }
        for(int i = 1000 ; i >= 1; i--){
            if(count[i] >= i) return i;
            count[i-1] += count[i];
        }
        return 0;
    }
};